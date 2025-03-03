package gregtech.common.metatileentities.multi.electric;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import gregtech.api.GTValues;
import gregtech.api.capability.*;
import gregtech.api.capability.impl.EnergyContainerList;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.pattern.BlockPattern;
import gregtech.api.pattern.FactoryBlockPattern;
import gregtech.api.pattern.MultiblockShapeInfo;
import gregtech.api.pattern.PatternMatchContext;
import gregtech.api.unification.material.Materials;
import gregtech.api.util.GTUtility;
import gregtech.api.util.TextFormattingUtil;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.ConfigHolder;
import gregtech.common.blocks.BlockComputerCasing;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.metatileentities.MetaTileEntities;
import gregtech.core.sound.GTSoundEvents;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.*;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class MetaTileEntityHPCA extends MultiblockWithDisplayBase implements IOpticalComputationProvider, IControllable {

    private static final double IDLE_TEMPERATURE = 200;
    private static final double DAMAGE_TEMPERATURE = 1000;

    private IEnergyContainer energyContainer;
    private IFluidHandler coolantHandler;
    private final HPCAGridHandler hpcaHandler = new HPCAGridHandler();

    private boolean isActive;
    private boolean isWorkingEnabled = true;
    private boolean hasNotEnoughEnergy;

    private double temperature = IDLE_TEMPERATURE; // start at idle temperature

    public MetaTileEntityHPCA(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId);
        this.energyContainer = new EnergyContainerList(new ArrayList<>());
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityHPCA(metaTileEntityId);
    }

    @Override
    protected void formStructure(PatternMatchContext context) {
        super.formStructure(context);
        this.energyContainer = new EnergyContainerList(getAbilities(MultiblockAbility.INPUT_ENERGY));
        this.coolantHandler = new FluidTankList(false, getAbilities(MultiblockAbility.IMPORT_FLUIDS));
        this.hpcaHandler.onStructureForm(getAbilities(MultiblockAbility.HPCA_COMPONENT));
    }

    @Override
    public void invalidateStructure() {
        super.invalidateStructure();
        this.energyContainer = new EnergyContainerList(new ArrayList<>());
        this.hpcaHandler.onStructureInvalidate();
    }

    @Override
    public int requestCWUt(int cwut, boolean simulate, @NotNull Collection<IOpticalComputationProvider> seen) {
        seen.add(this);
        return isActive() && !hasNotEnoughEnergy ? hpcaHandler.allocateCWUt(cwut, simulate) : 0;
    }

    @Override
    public int getMaxCWUt(@NotNull Collection<IOpticalComputationProvider> seen) {
        seen.add(this);
        return isActive() ? hpcaHandler.getMaxCWUt() : 0;
    }

    @Override
    public boolean canBridge(@NotNull Collection<IOpticalComputationProvider> seen) {
        seen.add(this);
        // don't show a problem if the structure is not yet formed
        return !isStructureFormed() || hpcaHandler.hasHPCABridge();
    }

    @Override
    protected void updateFormedValid() {
        consumeEnergy();
        if (isActive()) {
            // forcibly use active coolers at full rate if temperature is half-way to damaging temperature
            double midpoint = (DAMAGE_TEMPERATURE - IDLE_TEMPERATURE) / 2;
            double temperatureChange = hpcaHandler.calculateTemperatureChange(coolantHandler, temperature >= midpoint) / 2.0;
            if (temperature + temperatureChange <= IDLE_TEMPERATURE) {
                temperature = IDLE_TEMPERATURE;
            } else {
                temperature += temperatureChange;
            }
            if (temperature >= DAMAGE_TEMPERATURE) {
                hpcaHandler.attemptDamageHPCA();
            }
            hpcaHandler.tick();
        } else {
            hpcaHandler.clearComputationCache();
            // passively cool (slowly) if not active
            temperature = Math.max(IDLE_TEMPERATURE, temperature - 0.25);
        }
    }

    private void consumeEnergy() {
        int energyToConsume = hpcaHandler.getCurrentEUt();
        boolean hasMaintenance = ConfigHolder.machines.enableMaintenance && hasMaintenanceMechanics();
        if (hasMaintenance) {
            // 10% more energy per maintenance problem
            energyToConsume += getNumMaintenanceProblems() * energyToConsume / 10;
        }

        if (this.hasNotEnoughEnergy && energyContainer.getInputPerSec() > 19L * energyToConsume) {
            this.hasNotEnoughEnergy = false;
        }

        if (this.energyContainer.getEnergyStored() >= energyToConsume) {
            if (!hasNotEnoughEnergy) {
                long consumed = this.energyContainer.removeEnergy(energyToConsume);
                if (consumed == -energyToConsume) {
                    setActive(true);
                } else {
                    this.hasNotEnoughEnergy = true;
                    setActive(false);
                }
            }
        } else {
            this.hasNotEnoughEnergy = true;
            setActive(false);
        }
    }

    @Override
    protected @NotNull BlockPattern createStructurePattern() {
        return FactoryBlockPattern.start()
                .aisle("AA", "CC", "CC", "CC", "AA")
                .aisle("VA", "XV", "XV", "XV", "VA")
                .aisle("VA", "XV", "XV", "XV", "VA")
                .aisle("VA", "XV", "XV", "XV", "VA")
                .aisle("SA", "CC", "CC", "CC", "AA")
                .where('S', selfPredicate())
                .where('A', states(getAdvancedState()))
                .where('V', states(getVentState()))
                .where('X', abilities(MultiblockAbility.HPCA_COMPONENT))
                .where('C', states(getCasingState()).setMinGlobalLimited(5)
                        .or(abilities(MultiblockAbility.MAINTENANCE_HATCH).setExactLimit(1))
                        .or(abilities(MultiblockAbility.INPUT_ENERGY).setMinGlobalLimited(1))
                        .or(abilities(MultiblockAbility.IMPORT_FLUIDS).setMaxGlobalLimited(1))
                        .or(abilities(MultiblockAbility.COMPUTATION_DATA_TRANSMISSION).setExactLimit(1)))
                .build();
    }

    private static @NotNull IBlockState getCasingState() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_CASING);
    }

    private static @NotNull IBlockState getAdvancedState() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.ADVANCED_COMPUTER_CASING);
    }

    private static @NotNull IBlockState getVentState() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_HEAT_VENT);
    }

    @Override
    public List<MultiblockShapeInfo> getMatchingShapes() {
        List<MultiblockShapeInfo> shapeInfo = new ArrayList<>();
        MultiblockShapeInfo.Builder builder = MultiblockShapeInfo.builder()
                .aisle("AA", "EC", "MC", "HC", "AA")
                .aisle("VA", "6V", "3V", "0V", "VA")
                .aisle("VA", "7V", "4V", "1V", "VA")
                .aisle("VA", "8V", "5V", "2V", "VA")
                .aisle("SA", "CC", "CC", "OC", "AA")
                .where('S', MetaTileEntities.HIGH_PERFORMANCE_COMPUTING_ARRAY, EnumFacing.SOUTH)
                .where('A', getAdvancedState())
                .where('V', getVentState())
                .where('C', getCasingState())
                .where('E', MetaTileEntities.ENERGY_INPUT_HATCH[GTValues.LuV], EnumFacing.NORTH)
                .where('H', MetaTileEntities.FLUID_IMPORT_HATCH[GTValues.LV], EnumFacing.NORTH)
                .where('O', MetaTileEntities.COMPUTATION_HATCH_TRANSMITTER, EnumFacing.SOUTH)
                .where('M', () -> ConfigHolder.machines.enableMaintenance ? MetaTileEntities.MAINTENANCE_HATCH : getCasingState(), EnumFacing.NORTH);

        // a few example structures
        shapeInfo.add(builder.shallowCopy()
                .where('0', MetaTileEntities.HPCA_EMPTY_COMPONENT, EnumFacing.WEST)
                .where('1', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('2', MetaTileEntities.HPCA_EMPTY_COMPONENT, EnumFacing.WEST)
                .where('3', MetaTileEntities.HPCA_EMPTY_COMPONENT, EnumFacing.WEST)
                .where('4', MetaTileEntities.HPCA_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('5', MetaTileEntities.HPCA_EMPTY_COMPONENT, EnumFacing.WEST)
                .where('6', MetaTileEntities.HPCA_EMPTY_COMPONENT, EnumFacing.WEST)
                .where('7', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('8', MetaTileEntities.HPCA_EMPTY_COMPONENT, EnumFacing.WEST)
                .build());

        shapeInfo.add(builder.shallowCopy()
                .where('0', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('1', MetaTileEntities.HPCA_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('2', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('3', MetaTileEntities.HPCA_ACTIVE_COOLER_COMPONENT, EnumFacing.WEST)
                .where('4', MetaTileEntities.HPCA_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('5', MetaTileEntities.HPCA_BRIDGE_COMPONENT, EnumFacing.WEST)
                .where('6', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('7', MetaTileEntities.HPCA_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('8', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .build());

        shapeInfo.add(builder.shallowCopy()
                .where('0', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('1', MetaTileEntities.HPCA_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('2', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('3', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('4', MetaTileEntities.HPCA_ADVANCED_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('5', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('6', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('7', MetaTileEntities.HPCA_BRIDGE_COMPONENT, EnumFacing.WEST)
                .where('8', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .build());

        shapeInfo.add(builder.shallowCopy()
                .where('0', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('1', MetaTileEntities.HPCA_ADVANCED_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('2', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('3', MetaTileEntities.HPCA_ACTIVE_COOLER_COMPONENT, EnumFacing.WEST)
                .where('4', MetaTileEntities.HPCA_BRIDGE_COMPONENT, EnumFacing.WEST)
                .where('5', MetaTileEntities.HPCA_ACTIVE_COOLER_COMPONENT, EnumFacing.WEST)
                .where('6', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .where('7', MetaTileEntities.HPCA_ADVANCED_COMPUTATION_COMPONENT, EnumFacing.WEST)
                .where('8', MetaTileEntities.HPCA_HEAT_SINK_COMPONENT, EnumFacing.WEST)
                .build());

        return shapeInfo;
    }

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart sourcePart) {
        if (sourcePart == null) {
            return Textures.ADVANCED_COMPUTER_CASING; // controller
        }
        return Textures.COMPUTER_CASING; // multiblock parts
    }

    @Override
    protected @NotNull ICubeRenderer getFrontOverlay() {
        return Textures.HPCA_OVERLAY;
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        getFrontOverlay().renderOrientedState(renderState, translation, pipeline, getFrontFacing(), this.isActive(), this.isWorkingEnabled());
    }

    @Override
    public boolean isActive() {
        return super.isActive() && this.isActive;
    }

    public void setActive(boolean active) {
        if (this.isActive != active) {
            this.isActive = active;
            markDirty();
            if (getWorld() != null && !getWorld().isRemote) {
                writeCustomData(GregtechDataCodes.WORKABLE_ACTIVE, buf -> buf.writeBoolean(active));
            }
        }
    }

    @Override
    public boolean isWorkingEnabled() {
        return this.isWorkingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean isWorkingAllowed) {
        if (this.isWorkingEnabled != isWorkingAllowed) {
            this.isWorkingEnabled = isWorkingAllowed;
            markDirty();
            if (getWorld() != null && !getWorld().isRemote) {
                writeCustomData(GregtechDataCodes.WORKING_ENABLED, buf -> buf.writeBoolean(isWorkingEnabled));
            }
        }
    }

    @Override
    protected void addDisplayText(List<ITextComponent> textList) {
        super.addDisplayText(textList);
        if (isStructureFormed()) {
            textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.computation",
                    hpcaHandler.cachedCWUt, hpcaHandler.getMaxCWUt()));
            textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.energy",
                    TextFormattingUtil.formatNumbers(hpcaHandler.cachedEUt),
                    TextFormattingUtil.formatNumbers(hpcaHandler.getMaxEUt()),
                    GTValues.VNF[GTUtility.getTierByVoltage(hpcaHandler.getMaxEUt())]));

            int coolantDemand = hpcaHandler.getMaxCoolantDemand();
            if (coolantDemand > 0 && hpcaHandler.getCoolant() != null) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.coolant", coolantDemand));
            }

            int coolingDemand = hpcaHandler.getMaxCoolingDemand();
            int coolingProvided = hpcaHandler.getMaxCoolingAmount();
            textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.cooling")
                    .appendText(getDisplayCoolingColor(coolingProvided, coolingDemand) + " " + coolingProvided + " / " + coolingDemand));

            textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.temperature")
                    .appendText(getDisplayTemperatureColor() + " " + Math.round(temperature / 10.0D) + "°C"));

            if (!isWorkingEnabled()) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.work_paused"));
            } else if (isActive() && hpcaHandler.cachedCWUt > 0) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.running"));
            } else {
                textList.add(new TextComponentTranslation("gregtech.multiblock.idling"));
            }

            if (hasNotEnoughEnergy) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.not_enough_energy")
                        .setStyle(new Style().setColor(TextFormatting.RED)));
            }
        }
    }

    private TextFormatting getDisplayTemperatureColor() {
        if (temperature < 500) {
            return TextFormatting.GREEN;
        } else if (temperature < 750) {
            return TextFormatting.YELLOW;
        }
        return TextFormatting.RED;
    }

    private TextFormatting getDisplayCoolingColor(int provided, int demand) {
        if (provided >= demand) {
            return TextFormatting.GREEN;
        } else if (demand - provided >= 2) {
            return TextFormatting.YELLOW;
        }
        return TextFormatting.RED;
    }

    @Override
    protected void addWarningText(List<ITextComponent> textList) {
        super.addWarningText(textList);
        if (isStructureFormed()) {
            if (temperature > 500) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.warning_temperature")
                        .setStyle(new Style().setColor(TextFormatting.RED)));
                if (hpcaHandler.hasActiveCoolers()) {
                    textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.warning_temperature_active_cool")
                            .setStyle(new Style().setColor(TextFormatting.GRAY)));
                }
            }
            if (hasNotEnoughEnergy) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.not_enough_energy")
                        .setStyle(new Style().setColor(TextFormatting.RED)));
            }
            hpcaHandler.addWarnings(textList);
        }
    }

    @Override
    protected void addErrorText(List<ITextComponent> textList) {
        super.addErrorText(textList);
        if (isStructureFormed()) {
            if (temperature > 1000) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.error_temperature"));
            }
            hpcaHandler.addErrors(textList);
        }
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, @NotNull List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format("gregtech.machine.high_performance_computing_array.tooltip.1"));
        tooltip.add(I18n.format("gregtech.machine.high_performance_computing_array.tooltip.2"));
        tooltip.add(I18n.format("gregtech.machine.high_performance_computing_array.tooltip.3"));
    }

    @Override
    protected boolean shouldShowVoidingModeButton() {
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public SoundEvent getSound() {
        return GTSoundEvents.COMPUTATION;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("isActive", this.isActive);
        data.setBoolean("isWorkingEnabled", this.isWorkingEnabled);
        data.setDouble("temperature", this.temperature);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.isActive = data.getBoolean("isActive");
        this.isWorkingEnabled = data.getBoolean("isWorkingEnabled");
        this.temperature = data.getDouble("temperature");
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeBoolean(this.isActive);
        buf.writeBoolean(this.isWorkingEnabled);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.isActive = buf.readBoolean();
        this.isWorkingEnabled = buf.readBoolean();
    }

    @Override
    public void receiveCustomData(int dataId, @NotNull PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == GregtechDataCodes.WORKABLE_ACTIVE) {
            this.isActive = buf.readBoolean();
            scheduleRenderUpdate();
        } else if (dataId == GregtechDataCodes.WORKING_ENABLED) {
            this.isWorkingEnabled = buf.readBoolean();
            scheduleRenderUpdate();
        }
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }
        return super.getCapability(capability, side);
    }

    // Handles the logic of this structure's specific HPCA component grid
    public static class HPCAGridHandler {

        // structure info
        private final Set<IHPCAComponentHatch> components = new ObjectOpenHashSet<>();
        private final Set<IHPCACoolantProvider> coolantProviders = new ObjectOpenHashSet<>();
        private final Set<IHPCAComputationProvider> computationProviders = new ObjectOpenHashSet<>();
        private int numBridges;

        // transaction info
        private int allocatedCWUt;

        // cached gui info
        // holding these values past the computation clear because GUI is too "late" to read the state in time
        private int cachedEUt;
        private int cachedCWUt;

        public void onStructureForm(Collection<IHPCAComponentHatch> components) {
            reset();
            for (var component : components) {
                this.components.add(component);
                if (component instanceof IHPCACoolantProvider coolantProvider) {
                    this.coolantProviders.add(coolantProvider);
                }
                if (component instanceof IHPCAComputationProvider computationProvider) {
                    this.computationProviders.add(computationProvider);
                }
                if (component.isBridge()) {
                    this.numBridges++;
                }
            }
        }

        private void onStructureInvalidate() {
            reset();
        }

        private void reset() {
            clearComputationCache();
            components.clear();
            coolantProviders.clear();
            computationProviders.clear();
            numBridges = 0;
        }

        private void clearComputationCache() {
            allocatedCWUt = 0;
        }

        public void tick() {
            cachedCWUt = allocatedCWUt;
            cachedEUt = getCurrentEUt();
            if (allocatedCWUt != 0) {
                allocatedCWUt = 0;
            }
        }

        /**
         * Calculate the temperature differential this tick given active computation and consume coolant.
         *
         * @param coolantTank         The tank to drain coolant from.
         * @param forceCoolWithActive Whether active coolers should forcibly cool even if temperature is already
         *                            decreasing due to passive coolers. Used when the HPCA is running very hot.
         * @return The temperature change, can be positive or negative.
         */
        public double calculateTemperatureChange(IFluidHandler coolantTank, boolean forceCoolWithActive) {
            // calculate temperature increase
            int maxCWUt = Math.max(1, getMaxCWUt()); // behavior is no different setting this to 1 if it is 0
            int maxCoolingDemand = getMaxCoolingDemand();

            // temperature increase is proportional to the amount of actively used computation
            // a * (b / c)
            int temperatureIncrease = (int) Math.round(1.0 * maxCoolingDemand * allocatedCWUt / maxCWUt);

            // calculate temperature decrease
            int maxPassiveCooling = 0;
            int maxActiveCooling = 0;
            int maxCoolantDrain = 0;

            for (var coolantProvider : coolantProviders) {
                if (coolantProvider.isActiveCooler()) {
                    maxActiveCooling += coolantProvider.getCoolingAmount();
                    maxCoolantDrain += coolantProvider.getMaxCoolantPerTick();
                } else {
                    maxPassiveCooling += coolantProvider.getCoolingAmount();
                }
            }

            double temperatureChange = temperatureIncrease - maxPassiveCooling;
            // quick exit if no active cooling/coolant drain is present
            if (maxActiveCooling == 0 && maxCoolantDrain == 0) {
                return temperatureChange;
            }
            if (forceCoolWithActive || maxActiveCooling <= temperatureChange) {
                // try to fully utilize active coolers
                FluidStack coolantStack = coolantTank.drain(getCoolantStack(maxCoolantDrain), true);
                if (coolantStack != null) {
                    int coolantDrained = coolantStack.amount;
                    if (coolantDrained == maxCoolantDrain) {
                        // coolant requirement was fully met
                        temperatureChange -= maxActiveCooling;
                    } else {
                        // coolant requirement was only partially met, cool proportional to fluid amount drained
                        // a * (b / c)
                        temperatureChange -= maxActiveCooling * (1.0 * coolantDrained / maxCoolantDrain);
                    }
                }
            } else if (temperatureChange > 0) {
                // try to partially utilize active coolers to stabilize to zero
                double temperatureToDecrease = Math.min(temperatureChange, maxActiveCooling);
                int coolantToDrain = Math.max(1, (int) (maxCoolantDrain * (temperatureToDecrease / maxActiveCooling)));
                FluidStack coolantStack = coolantTank.drain(getCoolantStack(coolantToDrain), true);
                if (coolantStack != null) {
                    int coolantDrained = coolantStack.amount;
                    if (coolantDrained == coolantToDrain) {
                        // successfully stabilized to zero
                        return 0;
                    } else {
                        // coolant requirement was only partially met, cool proportional to fluid amount drained
                        // a * (b / c)
                        temperatureChange -= temperatureToDecrease * (1.0 * coolantDrained / coolantToDrain);
                    }
                }
            }
            return temperatureChange;
        }

        /**
         * Get the coolant stack for this HPCA. Eventually this could be made more diverse with different
         * coolants from different Active Cooler components, but currently it is just a fixed Fluid.
         */
        public FluidStack getCoolantStack(int amount) {
            return new FluidStack(getCoolant(), amount);
        }

        private Fluid getCoolant() {
            return Materials.PCBCoolant.getFluid();
        }

        /**
         * Roll a 1/200 chance to damage a HPCA component marked as damageable. Randomly selects the component.
         * If called every tick, this succeeds on average once every 10 seconds.
         */
        public void attemptDamageHPCA() {
            // 1% chance each tick to damage a component if running too hot
            if (GTValues.RNG.nextInt(200) == 0) {
                // randomize which component is actually damaged
                List<IHPCAComponentHatch> candidates = new ArrayList<>();
                for (var component : components) {
                    if (component.canBeDamaged()) {
                        candidates.add(component);
                    }
                }
                if (!candidates.isEmpty()) {
                    candidates.get(GTValues.RNG.nextInt(candidates.size())).setDamaged(true);
                }
            }
        }

        /** Allocate computation on a given request. Allocates for one tick. */
        public int allocateCWUt(int cwut, boolean simulate) {
            int maxCWUt = getMaxCWUt();
            int availableCWUt = maxCWUt - this.allocatedCWUt;
            int toAllocate = Math.min(cwut, availableCWUt);
            if (!simulate) {
                this.allocatedCWUt += toAllocate;
            }
            return toAllocate;
        }

        /** How much CWU/t is currently allocated for this tick. */
        public int getAllocatedCWUt() {
            return allocatedCWUt;
        }

        /** The maximum amount of CWUs (Compute Work Units) created per tick. */
        public int getMaxCWUt() {
            int maxCWUt = 0;
            for (var computationProvider : computationProviders) {
                maxCWUt += computationProvider.getCWUPerTick();
            }
            return maxCWUt;
        }

        /** The current EU/t this HPCA should use, considering passive drain, current computation, etc.. */
        public int getCurrentEUt() {
            int maximumCWUt = Math.max(1, getMaxCWUt()); // behavior is no different setting this to 1 if it is 0
            int maximumEUt = getMaxEUt();
            int upkeepEUt = getUpkeepEUt();

            if (maximumEUt == upkeepEUt) {
                return maximumEUt;
            }

            // energy draw is proportional to the amount of actively used computation
            // a + c(b - a) / d
            return upkeepEUt + ((maximumEUt - upkeepEUt) * allocatedCWUt / maximumCWUt);
        }

        /** The amount of EU/t this HPCA uses just to stay on with 0 output computation. */
        public int getUpkeepEUt() {
            int upkeepEUt = 0;
            for (var component : components) {
                upkeepEUt += component.getUpkeepEUt();
            }
            return upkeepEUt;
        }

        /** The maximum EU/t that this HPCA could ever use with the given configuration. */
        public int getMaxEUt() {
            int maximumEUt = 0;
            for (var component : components) {
                maximumEUt += component.getMaxEUt();
            }
            return maximumEUt;
        }

        /** Whether this HPCA has a Bridge to allow connecting to other HPCA's */
        public boolean hasHPCABridge() {
            return numBridges > 0;
        }

        /** Whether this HPCA has any cooling providers which are actively cooled. */
        public boolean hasActiveCoolers() {
            for (var coolantProvider : coolantProviders) {
                if (coolantProvider.isActiveCooler()) return true;
            }
            return false;
        }

        /** How much cooling this HPCA can provide. NOT related to coolant fluid consumption. */
        public int getMaxCoolingAmount() {
            int maxCooling = 0;
            for (var coolantProvider : coolantProviders) {
                maxCooling += coolantProvider.getCoolingAmount();
            }
            return maxCooling;
        }

        /** How much cooling this HPCA can require. NOT related to coolant fluid consumption. */
        public int getMaxCoolingDemand() {
            int maxCooling = 0;
            for (var computationProvider : computationProviders) {
                maxCooling += computationProvider.getCoolingPerTick();
            }
            return maxCooling;
        }

        /** How much coolant this HPCA can consume in a tick, in L/t. */
        public int getMaxCoolantDemand() {
            int maxCoolant = 0;
            for (var coolantProvider : coolantProviders) {
                maxCoolant += coolantProvider.getMaxCoolantPerTick();
            }
            return maxCoolant;
        }

        public void addWarnings(List<ITextComponent> textList) {
            List<ITextComponent> warnings = new ArrayList<>();
            if (numBridges > 1) {
                warnings.add(new TextComponentTranslation("gregtech.multiblock.hpca.warning_multiple_bridges"));
            }
            if (computationProviders.isEmpty()) {
                warnings.add(new TextComponentTranslation("gregtech.multiblock.hpca.warning_no_computation"));
            }
            if (getMaxCoolingDemand() > getMaxCoolingAmount()) {
                warnings.add(new TextComponentTranslation("gregtech.multiblock.hpca.warning_low_cooling"));
            }
            if (!warnings.isEmpty()) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.warning_structure_header"));
                textList.addAll(warnings);
            }
        }

        public void addErrors(List<ITextComponent> textList) {
            if (components.stream().anyMatch(IHPCAComponentHatch::isDamaged)) {
                textList.add(new TextComponentTranslation("gregtech.multiblock.hpca.error_damaged"));
            }
        }
    }
}
