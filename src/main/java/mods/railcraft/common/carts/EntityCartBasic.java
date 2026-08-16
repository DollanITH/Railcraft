/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2019
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.common.carts;

import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SuppressWarnings("UnnecessaryThis")
public class EntityCartBasic extends EntityMinecartEmpty implements IRailcraftCart {

    public EntityCartBasic(World world) {
        super(world);
    }

    public EntityCartBasic(World world, double x, double y, double z) {
        super(world, x, y, z);
    }

    @Override
    protected void entityInit() {
        super.entityInit();
        cartInit();
    }

    @Override
    public float getMaxCartSpeedOnRail() {
        return 5.0F;
    }

    @Override
    public double getMountedYOffset() {
        return 0.789;
    }

    @Override
    public boolean shouldRiderSit() {
        return false;
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        saveToNBT(compound);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        loadFromNBT(compound);
    }

    @Override
    public EntityMinecart.Type getType() {
        return Type.RIDEABLE;
    }

    @Override
    public IRailcraftCartContainer getCartType() {
        return RailcraftCarts.BASIC;
    }

    @Override
    public ItemStack getCartItem() {
        return createCartItem(this);
    }

    @Override
    public void killMinecart(DamageSource par1DamageSource) {
        killAndDrop(this);
    }

    /**
     * Checks if the entity is in range to render.
     */
    @Override
    @SideOnly(Side.CLIENT)
    public boolean isInRangeToRenderDist(double distance) {
        return CartTools.isInRangeToRenderDist(this, distance);
    }

    @Override
    protected void moveAlongTrack(BlockPos pos, IBlockState state) {
        this.fallDistance = 0.0F;
        Vec3d vec3d = this.getPos(this.posX, this.posY, this.posZ);
        this.posY = (double) pos.getY();
        boolean boosted = false;
        boolean unpowered = false;
        BlockRailBase blockrailbase = (BlockRailBase) state.getBlock();

        if (blockrailbase == Blocks.GOLDEN_RAIL) {
            boosted = state.getValue(BlockRailPowered.POWERED);
            unpowered = !boosted;
        }

        double slopeAdjustment = getSlopeAdjustment();
        BlockRailBase.EnumRailDirection blockrailbase$enumraildirection = blockrailbase.getRailDirection(world, pos, state, this);

        // 调试信息：输出轨道方向变化
        if (world.isRemote) {
            System.out.println("Cart at " + pos + " rail direction: " + blockrailbase$enumraildirection +
                              " speed: " + Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ));
        }

        // 保守的弯道优化：仅小幅减少损失
        if (mods.railcraft.common.blocks.tracks.TrackShapeHelper.isTurn(blockrailbase$enumraildirection)) {
            conservativeCornerOptimization(blockrailbase$enumraildirection);
        }

        switch (blockrailbase$enumraildirection) {
            case ASCENDING_EAST:
                this.motionX -= slopeAdjustment;
                ++this.posY;
                conservativeSlopeOptimization(blockrailbase$enumraildirection);
                break;
            case ASCENDING_WEST:
                this.motionX += slopeAdjustment;
                ++this.posY;
                conservativeSlopeOptimization(blockrailbase$enumraildirection);
                break;
            case ASCENDING_NORTH:
                this.motionZ += slopeAdjustment;
                ++this.posY;
                conservativeSlopeOptimization(blockrailbase$enumraildirection);
                break;
            case ASCENDING_SOUTH:
                this.motionZ -= slopeAdjustment;
                ++this.posY;
                conservativeSlopeOptimization(blockrailbase$enumraildirection);
                break;
        }

        // 检查是否是从侧边接近的矿车
        boolean isSideApproaching = isSideApproachingCart(pos, blockrailbase$enumraildirection);

        int[][] aint;
        if (isSideApproaching) {
            // 对于侧向接近的矿车，使用直行矩阵
            aint = getSideApproachMatrix(blockrailbase$enumraildirection, this);
            System.out.println("DEBUG: Side approaching cart detected, using straight matrix");
        } else {
            aint = MATRIX[blockrailbase$enumraildirection.getMetadata()];
        }

        double d1 = (double) (aint[1][0] - aint[0][0]);
        double d2 = (double) (aint[1][2] - aint[0][2]);
        double d3 = Math.sqrt(d1 * d1 + d2 * d2);
        double d4 = this.motionX * d1 + this.motionZ * d2;

        if (d4 < 0.0D) {
            d1 = -d1;
            d2 = -d2;
        }

        double d5 = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

        if (d5 > 2.0D) {
            d5 = 2.0D;
        }

        // 使用原始逻辑，保持稳定性
        this.motionX = d5 * d1 / d3;
        this.motionZ = d5 * d2 / d3;

        // 调试输出
        if (isSideApproaching) {
            System.out.println("DEBUG: Cart from side, new direction: X=" + this.motionX + ", Z=" + this.motionZ);
        }
        Entity entity = this.getPassengers().isEmpty() ? null : this.getPassengers().get(0);

        if (entity instanceof EntityLivingBase) {
            double d6 = (double) ((EntityLivingBase) entity).moveForward;

            if (d6 > 0.0D) {
                double d7 = -Math.sin((double) (entity.rotationYaw * 0.017453292F));
                double d8 = Math.cos((double) (entity.rotationYaw * 0.017453292F));
                double d9 = this.motionX * this.motionX + this.motionZ * this.motionZ;

                if (d9 < 0.01D) {
                    this.motionX += d7 * 0.02D; // Railcraft: decrease entity pulling power
                    this.motionZ += d8 * 0.02D; // Railcraft #316
                    unpowered = false;
                }
            }
        }

        if (unpowered && shouldDoRailFunctions()) {
            double d17 = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

            if (d17 < 0.03D) {
                this.motionX *= 0.0D;
                this.motionY *= 0.0D;
                this.motionZ *= 0.0D;
            } else {
                this.motionX *= 0.5D;
                this.motionY *= 0.0D;
                this.motionZ *= 0.5D;
            }
        }

        double d18 = (double) pos.getX() + 0.5D + (double) aint[0][0] * 0.5D;
        double d19 = (double) pos.getZ() + 0.5D + (double) aint[0][2] * 0.5D;
        double d20 = (double) pos.getX() + 0.5D + (double) aint[1][0] * 0.5D;
        double d21 = (double) pos.getZ() + 0.5D + (double) aint[1][2] * 0.5D;
        d1 = d20 - d18;
        d2 = d21 - d19;
        double d10;

        if (d1 == 0.0D) {
            this.posX = (double) pos.getX() + 0.5D;
            d10 = this.posZ - (double) pos.getZ();
        } else if (d2 == 0.0D) {
            this.posZ = (double) pos.getZ() + 0.5D;
            d10 = this.posX - (double) pos.getX();
        } else {
            double d11 = this.posX - d18;
            double d12 = this.posZ - d19;
            d10 = (d11 * d1 + d12 * d2) * 2.0D;
        }

        this.posX = d18 + d1 * d10;
        this.posZ = d19 + d2 * d10;
        this.setPosition(this.posX, this.posY, this.posZ);
        this.moveMinecartOnRail(pos);

        if (aint[0][1] != 0 && MathHelper.floor(this.posX) - pos.getX() == aint[0][0] && MathHelper.floor(this.posZ) - pos.getZ() == aint[0][2]) {
            this.setPosition(this.posX, this.posY + (double) aint[0][1], this.posZ);
        } else if (aint[1][1] != 0 && MathHelper.floor(this.posX) - pos.getX() == aint[1][0] && MathHelper.floor(this.posZ) - pos.getZ() == aint[1][2]) {
            this.setPosition(this.posX, this.posY + (double) aint[1][1], this.posZ);
        }

        this.applyDrag();
        Vec3d vec3d1 = this.getPos(this.posX, this.posY, this.posZ);

        if (vec3d1 != null && vec3d != null) {
            double d14 = (vec3d.y - vec3d1.y) * 0.05D;
            d5 = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

            if (d5 > 0.0D) {
                this.motionX = this.motionX / d5 * (d5 + d14);
                this.motionZ = this.motionZ / d5 * (d5 + d14);
            }

            this.setPosition(this.posX, vec3d1.y, this.posZ);
        }

        int j = MathHelper.floor(this.posX);
        int i = MathHelper.floor(this.posZ);

        if (j != pos.getX() || i != pos.getZ()) {
            d5 = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
            this.motionX = d5 * (double) (j - pos.getX());
            this.motionZ = d5 * (double) (i - pos.getZ());
        }


        if (shouldDoRailFunctions()) {
            ((BlockRailBase) state.getBlock()).onMinecartPass(world, this, pos);
        }

        if (boosted && shouldDoRailFunctions()) {
            double d15 = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

            if (d15 > 0.01D) {
                this.motionX += this.motionX / d15 * 0.06D;
                this.motionZ += this.motionZ / d15 * 0.06D;
            } else if (blockrailbase$enumraildirection == BlockRailBase.EnumRailDirection.EAST_WEST) {
                if (this.world.getBlockState(pos.west()).isNormalCube()) {
                    this.motionX = 0.02D;
                } else if (this.world.getBlockState(pos.east()).isNormalCube()) {
                    this.motionX = -0.02D;
                }
            } else if (blockrailbase$enumraildirection == BlockRailBase.EnumRailDirection.NORTH_SOUTH) {
                if (this.world.getBlockState(pos.north()).isNormalCube()) {
                    this.motionZ = 0.02D;
                } else if (this.world.getBlockState(pos.south()).isNormalCube()) {
                    this.motionZ = -0.02D;
                }
            }
        }
    }

    //    @Override
//    protected double getDrag() {
//        if (RailcraftConfig.adjustBasicCartDrag()) {
//            return CartConstants.STANDARD_DRAG;
//        }
//        return super.getDrag();
//    }
//    @Override
//    public void onUpdate() {
//        if (Game.isHost(world) && world instanceof WorldServer) {
//            int blockId = world.getBlockId((int) posX, (int) posY, (int) posZ);
//
//            if (blockId == Block.portal.blockID) {
//                setInPortal();
//            }
//
//            if (inPortal) {
//                MinecraftServer mc = ((WorldServer) world).getMinecraftServer();
//                if (mc.getAllowNether()) {
//                    int maxPortalTime = getMaxInPortalTime();
//                    if (ridingEntity == null && field_82153_h++ >= maxPortalTime) {
//                        field_82153_h = maxPortalTime;
//                        timeUntilPortal = getPortalCooldown();
//                        byte dim;
//
//                        if (world.provider.getDimensionId() == -1) {
//                            dim = 0;
//                        } else {
//                            dim = -1;
//                        }
//
//                        Entity rider = riddenByEntity;
//                        if (rider != null) {
//                            rider.setInPortal();
//                            rider.timeUntilPortal = rider.getPortalCooldown();
//                            rider.travelToDimension(dim);
//                        }
//                        travelToDimension(dim);
//                    }
//
//                    inPortal = false;
//                }
//            }
//        }
//
//        super.onUpdate();
//    }

    @Override
    public void moveMinecartOnRail(BlockPos pos) {
        double mX = motionX;
        double mZ = motionZ;

//        if (this.riddenByEntity != null)
//        {
//            mX *= 0.75D;
//            mZ *= 0.75D;
//        }

        double max = getMaxSpeed();
        mX = MathHelper.clamp(mX, -max, max);
        mZ = MathHelper.clamp(mZ, -max, max);
        move(MoverType.SELF, mX, 0.0D, mZ);
    }

    /**
     * Called every tick the minecart is on an activator rail.
     *
     * We change this to disable the passenger removal. Use Disembarking Kits instead.
     */
    @Override
    public void onActivatorRailPass(int x, int y, int z, boolean receivingPower) {
        if (receivingPower && getRollingAmplitude() == 0) {
            setRollingDirection(-getRollingDirection());
            setRollingAmplitude(10);
            setDamage(50.0F);
            markVelocityChanged();
        }
    }

    /**
     * 保守的弯道优化 - 仅小幅减少损失，避免脱轨
     */
    private void conservativeCornerOptimization(BlockRailBase.EnumRailDirection direction) {
        // 计算当前速度
        double currentSpeed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

        // 保守策略：仅损失2%的速度（而非0.5%）
        double cornerSpeedMultiplier = 0.98;

        // 应用弯道速度保持
        this.motionX *= cornerSpeedMultiplier;
        this.motionZ *= cornerSpeedMultiplier;

        // 保持原始方向计算，避免复杂的角度插值
    }

    /**
     * 保守的坡道优化 - 防止速度过快
     */
    private void conservativeSlopeOptimization(BlockRailBase.EnumRailDirection direction) {
        if (!direction.isAscending()) return;

        double currentSpeed = Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);

        // 保守的重力损失：3%
        double gravityLoss = 0.03;

        // 保守的补偿：仅补偿1%
        double boostFactor = 1.01;

        // 应用坡道物理
        this.motionX *= (1.0 - gravityLoss) * boostFactor;
        this.motionZ *= (1.0 - gravityLoss) * boostFactor;

        // 保守的速度限制
        double maxSlopeSpeed = 1.2f;
        if (currentSpeed * boostFactor > maxSlopeSpeed) {
            double ratio = maxSlopeSpeed / (currentSpeed * boostFactor);
            this.motionX *= ratio;
            this.motionZ *= ratio;
        }
    }

    /**
     * 检查矿车是否从侧边接近道岔
     */
    private boolean isSideApproachingCart(BlockPos pos, BlockRailBase.EnumRailDirection trackDirection) {
        // 计算矿车相对于道岔中心的位置
        double cartX = this.posX;
        double cartZ = this.posZ;
        double switchX = pos.getX() + 0.5;
        double switchZ = pos.getZ() + 0.5;

        // 计算相对位置
        double dx = cartX - switchX;
        double dz = cartZ - switchZ;

        // 根据轨道方向判断是否从侧边接近
        if (trackDirection == BlockRailBase.EnumRailDirection.NORTH_SOUTH) {
            // 南北向轨道，侧边是东西方向
            // 如果X方向偏移大于Z方向偏移，认为是侧向接近
            return Math.abs(dx) > Math.abs(dz);
        } else if (trackDirection == BlockRailBase.EnumRailDirection.EAST_WEST) {
            // 东西向轨道，侧边是南北方向
            // 如果Z方向偏移大于X方向偏移，认为是侧向接近
            return Math.abs(dz) > Math.abs(dx);
        }

        // 对于其他轨道类型，使用保守判断
        return Math.abs(dx) > 0.3 || Math.abs(dz) > 0.3;
    }

    /**
     * 为侧向接近的矿车生成直行矩阵
     */
    private int[][] getSideApproachMatrix(BlockRailBase.EnumRailDirection trackDirection, EntityMinecart cart) {
        // 根据矿车的运动方向生成直行矩阵
        double speedX = cart.motionX;
        double speedZ = cart.motionZ;

        // 归一化运动方向
        double magnitude = Math.sqrt(speedX * speedX + speedZ * speedZ);
        if (magnitude == 0) {
            // 如果没有运动，使用默认直行
            return getStraightMatrix(trackDirection);
        }

        double normalizedX = speedX / magnitude;
        double normalizedZ = speedZ / magnitude;

        // 创建直行矩阵
        return new int[][]{
            {0, 0, 0}, // 第一个点不重要
            {(int) Math.round(normalizedX), 0, (int) Math.round(normalizedZ)} // 运动方向
        };
    }

    /**
     * 获取指定方向的直行矩阵
     */
    private int[][] getStraightMatrix(BlockRailBase.EnumRailDirection trackDirection) {
        switch (trackDirection) {
            case NORTH_SOUTH:
                return new int[][]{{0, 0, 0}, {0, 0, 1}}; // 南北向直行
            case EAST_WEST:
                return new int[][]{{0, 0, 0}, {1, 0, 0}};  // 东西向直行
            default:
                return new int[][]{{0, 0, 0}, {0, 0, 1}};  // 默认南北向
        }
    }
}
