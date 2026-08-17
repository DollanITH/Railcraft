/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2020
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.common.carts;

import mods.railcraft.api.carts.ILinkableCart;
import mods.railcraft.api.carts.ILinkageManager;
import mods.railcraft.api.tracks.TrackToolsAPI;
import mods.railcraft.common.blocks.tracks.TrackTools;
import mods.railcraft.common.blocks.tracks.behaivor.HighSpeedTools;
import mods.railcraft.common.blocks.tracks.TrackShapeHelper;
import mods.railcraft.common.modules.ModuleLocomotives;
import mods.railcraft.common.modules.RailcraftModuleManager;
import mods.railcraft.common.util.collections.Streams;
import mods.railcraft.common.util.misc.Vec2D;
import net.minecraft.entity.item.EntityMinecart;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.BlockRailBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class LinkageHandler {
    public static final String LINK_A_TIMER = "linkA_timer";
    public static final String LINK_B_TIMER = "linkB_timer";
    public static final double LINK_DRAG = 0.98;
    public static final float MAX_DISTANCE = 8F;
    private static final float STIFFNESS = 0.8F;  // Increased from 0.7 for better coupling stability
    private static final float HS_STIFFNESS = 0.75F;  // Increased from 0.7 for high-speed stability
    //    private static final float TRANSFER = 0.15f;
    private static final float DAMPING = 0.2F;  // Already optimized
    private static final float HS_DAMPING = 0.15F;  // Already optimized
    private static final float FORCE_LIMITER = 8.0F;  // Increased from 6.0 for stronger forces

    // Speed limiting constants for coupled trains
    private static final float MAX_COUPLED_SPEED_STRAIGHT = 1.0f;
    private static final float MAX_COUPLED_SPEED_CURVE = 0.64f;
    private static final int MIN_COUPLED_LENGTH_SPEED = 2;

    // Order maintenance constants
    private static final float MIN_LINK_DISTANCE = 0.5f;
    private static final float MAX_LINK_DISTANCE = 8.0f;
    private static final int ORDER_CHECK_INTERVAL = 10;
    private static final Map<EntityMinecart, Integer> orderCheckTimers = new HashMap<>();
    //    private static final int TICK_HISTORY = 200;
    private static LinkageHandler instance;
//    private static Map<EntityMinecart, CircularVec3Queue> history = new MapMaker().weakKeys().makeMap();

    private LinkageHandler() {
    }

    public static LinkageHandler getInstance() {
        if (instance == null)
            instance = new LinkageHandler();
        return instance;
    }

    /**
     * Returns the optimal distance between two linked carts that the
     * LinkageHandler will attempt to maintain at all times.
     *
     * @param cart1 EntityMinecart
     * @param cart2 EntityMinecart
     * @return The optimal distance
     */
    private float getOptimalDistance(EntityMinecart cart1, EntityMinecart cart2) {
        float dist = 0;
        if (cart1 instanceof ILinkableCart)
            dist += ((ILinkableCart) cart1).getOptimalDistance(cart2);
        else
            dist += ILinkageManager.OPTIMAL_DISTANCE;
        if (cart2 instanceof ILinkableCart)
            dist += ((ILinkableCart) cart2).getOptimalDistance(cart1);
        else
            dist += ILinkageManager.OPTIMAL_DISTANCE;
        return dist;
    }

    /**
     * Calculate maximum speed for a coupled train based on track conditions and train length
     */
    private float getMaxCoupledSpeed(EntityMinecart cart) {
        Train.get(cart).ifPresent(train -> {
            if (train.size() < MIN_COUPLED_LENGTH_SPEED) {
                return;
            }

            boolean onCurve = isOnCurve(cart);
            float maxSpeed = onCurve ? MAX_COUPLED_SPEED_CURVE : MAX_COUPLED_SPEED_STRAIGHT;

            // Reduce speed based on train length
            float lengthFactor = Math.max(0.5f, 1.0f - (train.size() - MIN_COUPLED_LENGTH_SPEED) * 0.1f);
            maxSpeed *= lengthFactor;

            // Apply the speed limit to the train
            for (EntityMinecart trainCart : train) {
                double currentSpeed = Math.sqrt(trainCart.motionX * trainCart.motionX + trainCart.motionZ * trainCart.motionZ);
                if (currentSpeed > maxSpeed) {
                    double factor = maxSpeed / currentSpeed;
                    trainCart.motionX *= factor;
                    trainCart.motionZ *= factor;
                }
            }
        });

        return cart.getMaxCartSpeedOnRail();
    }

    @SuppressWarnings("SimplifiableIfStatement")
    private boolean canCartBeAdjustedBy(EntityMinecart cart1, EntityMinecart cart2) {
        if (cart1 == cart2)
            return false;
        if (cart1 instanceof ILinkableCart && !((ILinkableCart) cart1).canBeAdjusted(cart2))
            return false;
        return !TrackToolsAPI.isCartLockedDown(cart1);
    }

    /**
     * This is where the physics magic actually gets performed. It uses Spring
     * Forces and Damping Forces to maintain a fixed distance between carts.
     *
     * @param cart1 EntityMinecart
     * @param cart2 EntityMinecart
     */
    private void adjustVelocity(EntityMinecart cart1, EntityMinecart cart2, LinkageManager.LinkType linkType) {
        String timer = LINK_A_TIMER;
        if (linkType == LinkageManager.LinkType.LINK_B)
            timer = LINK_B_TIMER;
        if (cart1.world.provider.getDimension() != cart2.world.provider.getDimension()) {
            short count = cart1.getEntityData().getShort(timer);
            count++;
            if (count > 200) {
                LinkageManager.INSTANCE.breakLink(cart1, cart2);
                LinkageManager.printDebug("Reason For Broken Link: Carts in different dimensions.");
            }
            cart1.getEntityData().setShort(timer, count);
            return;
        }
        cart1.getEntityData().setShort(timer, (short) 0);

        double dist = cart1.getDistance(cart2);
        if (dist > MAX_DISTANCE) {
            LinkageManager.INSTANCE.breakLink(cart1, cart2);
            LinkageManager.printDebug("Reason For Broken Link: Max distance exceeded.");
            return;
        }

        boolean adj1 = canCartBeAdjustedBy(cart1, cart2);
        boolean adj2 = canCartBeAdjustedBy(cart2, cart1);

        Vec2D cart1Pos = new Vec2D(cart1);
        Vec2D cart2Pos = new Vec2D(cart2);

        Vec2D unit = Vec2D.unit(cart2Pos, cart1Pos);

        // Energy transfer

//        double transX = TRANSFER * (cart2.motionX - cart1.motionX);
//        double transZ = TRANSFER * (cart2.motionZ - cart1.motionZ);
//
//        transX = limitForce(transX);
//        transZ = limitForce(transZ);
//
//        if(adj1) {
//            cart1.motionX += transX;
//            cart1.motionZ += transZ;
//        }
//
//        if(adj2) {
//            cart2.motionX -= transX;
//            cart2.motionZ -= transZ;
//        }

        // Spring force

        float optDist = getOptimalDistance(cart1, cart2);
        double stretch = dist - optDist;
//        stretch = Math.max(0.0, stretch);
//        if(Math.abs(stretch) > 0.5) {
//            stretch *= 2;
//        }

        boolean highSpeed = HighSpeedTools.isTravellingHighSpeed(cart1);

        double stiffness = highSpeed ? HS_STIFFNESS : STIFFNESS;
        double springX = stiffness * stretch * unit.getX();
        double springZ = stiffness * stretch * unit.getY();

        springX = limitForce(springX);
        springZ = limitForce(springZ);

        if (adj1) {
            cart1.motionX += springX;
            cart1.motionZ += springZ;
        }

        if (adj2) {
            cart2.motionX -= springX;
            cart2.motionZ -= springZ;
        }

        // Damping

        Vec2D cart1Vel = new Vec2D(cart1.motionX, cart1.motionZ);
        Vec2D cart2Vel = new Vec2D(cart2.motionX, cart2.motionZ);

        double dot = Vec2D.subtract(cart2Vel, cart1Vel).dotProduct(unit);

        double damping = highSpeed ? HS_DAMPING : DAMPING;
        double dampX = damping * dot * unit.getX();
        double dampZ = damping * dot * unit.getY();

        dampX = limitForce(dampX);
        dampZ = limitForce(dampZ);

        if (adj1) {
            cart1.motionX += dampX;
            cart1.motionZ += dampZ;
        }

        if (adj2) {
            cart2.motionX -= dampX;
            cart2.motionZ -= dampZ;
        }

        // Apply speed limits for coupled trains
        boolean linked = adj1 || adj2;
        if (linked) {
            float maxSpeed = getMaxCoupledSpeed(cart1);
            double speed1 = Math.sqrt(cart1.motionX * cart1.motionX + cart1.motionZ * cart1.motionZ);
            double speed2 = Math.sqrt(cart2.motionX * cart2.motionX + cart2.motionZ * cart2.motionZ);

            if (speed1 > maxSpeed) {
                double factor = maxSpeed / speed1;
                cart1.motionX *= factor;
                cart1.motionZ *= factor;
            }

            if (speed2 > maxSpeed) {
                double factor = maxSpeed / speed2;
                cart2.motionX *= factor;
                cart2.motionZ *= factor;
            }

            // Monitor coupling tension
            monitorCouplingTension(cart1, cart2);
        }
    }
    private double limitForce(double force) {
        return Math.copySign(Math.min(Math.abs(force), FORCE_LIMITER), force);
    }

    /**
     * 检查矿车是否在弯道上
     */
    private boolean isOnCurve(EntityMinecart cart) {
        BlockPos pos = new BlockPos(cart);
        // 先检查是否有轨道，避免异常
        if (!TrackTools.isRailBlockAt(cart.world, pos)) {
            return false; // 没有轨道，不算弯道
        }
        BlockRailBase.EnumRailDirection direction = TrackTools.getTrackDirection(cart.world, pos, cart);
        return TrackShapeHelper.isTurn(direction);
    }

    /**
     * 检查是否应该应用额外减速
     */
    private boolean shouldApplyDrag(EntityMinecart cart) {
        // 在弯道时不应用额外减速，避免过度减速
        if (isOnCurve(cart)) {
            return false;
        }

        // 高速时减少减速效果
        if (HighSpeedTools.isTravellingHighSpeed(cart)) {
            return false;
        }

        return true;
    }

    /**
     * This function inspects the links and determines if any physics
     * adjustments need to be made.
     *
     * @param cart EntityMinecart
     */
    private void adjustCart(EntityMinecart cart) {
        if (isLaunched(cart))
            return;

        if (isOnElevator(cart))
            return;

        boolean linkedA = adjustLinkedCart(cart, LinkageManager.LinkType.LINK_A);
        boolean linkedB = adjustLinkedCart(cart, LinkageManager.LinkType.LINK_B);
        boolean linked = linkedA || linkedB;

        // Apply new safety features for coupled trains
        if (linked) {
            // Check order integrity
            checkOrderIntegrity(cart);

            // Check for derailment
            checkProgressiveDerailment(cart);

            // Apply speed limiting
            enforceSpeedLimit(cart);
        }

        // Centroid
//        List<BlockPos> points = Train.streamCarts(cart).map(Entity::getPosition).collect(Collectors.toList());
//        Vec2D centroid = new Vec2D(MathTools.centroid(points));
//
//        Vec2D cartPos = new Vec2D(cart);
//        Vec2D unit = Vec2D.unit(cartPos, centroid);
//
//        double amount = 0.2;
//        double pushX = amount * unit.getX();
//        double pushZ = amount * unit.getY();
//
//        pushX = limitForce(pushX);
//        pushZ = limitForce(pushZ);
//
//        cart.motionX += pushX;
//        cart.motionZ += pushZ;

        // Drag - 只在非弯道时应用额外减速
        if (linked && RailcraftModuleManager.isModuleEnabled(ModuleLocomotives.class) && shouldApplyDrag(cart)) {
            cart.motionX *= LINK_DRAG;
            cart.motionZ *= LINK_DRAG;
        }

        // Speed & End Drag
        Train.get(cart).ifPresent(train -> {
            if (train.isTrainEnd(cart)) {
                train.refreshMaxSpeed();
//                if (linked && !(cart instanceof EntityLocomotive)) {
//                    double drag = 0.97;
//                    cart.motionX *= drag;
//                    cart.motionZ *= drag;
//                }
            }
        });

    }

    private boolean adjustLinkedCart(EntityMinecart cart, LinkageManager.LinkType linkType) {
        boolean linked = false;
        LinkageManager lm = LinkageManager.INSTANCE;
        EntityMinecart link = lm.getLinkedCart(cart, linkType);
        if (link != null) {
            // sanity check to ensure links are consistent
            if (!lm.areLinked(cart, link)) {
                boolean success = lm.repairLink(cart, link);
                //TODO something should happen here
            }
            if (!isLaunched(link) && !isOnElevator(link)) {
                linked = true;
                adjustVelocity(cart, link, linkType);
//                adjustCartFromHistory(cart, link);
            }
        }
        return linked;
    }

//    /**
//     * Determines whether a cart is leading another.
//     *
//     * @param leader EntityMinecart
//     * @param follower EntityMinecart
//     * @return true if leader is leading follower
//     */
//    private boolean isCartLeading(EntityMinecart leader, EntityMinecart follower) {
//        return true; // magic goes here
//    }

//    /**
//     * Adjust the current cart's position based on the linked cart its following
//     * so that it follows the same path at a set distance.
//     *
//     * @param current EntityMinecart
//     * @param linked EntityMinecart
//     */
//    private void adjustCartFromHistory(EntityMinecart current, EntityMinecart linked) {
//        // If we are leading, we don't want to adjust anything
//        if (isCartLeading(current, linked))
//            return;
//
//        CircularVec3Queue leaderHistory = history.get(linked);
//
//        // Optimal distance is how far apart the carts should be
//        double optimalDist = getOptimalDistance(current, linked);
//        optimalDist *= optimalDist;
//
//        double currentDistance = linked.getDistanceSq(current);
//
//        // Search the history for the point closest to the optimal distance.
//        // There may be some issues with it choosing the wrong side of the cart.
//        // Probably needs some kind of logic to compare the distance from the
//        // new position to the current position and determine if its a valid position.
//        Vec3 closestPoint = null;
//        Vec3 linkedVec = new Vec3(linked.posX, linked.posY, linked.posZ);
//        double distance = Math.abs(optimalDist - currentDistance);
//        for (Vec3 pos : leaderHistory) {
//            double historyDistance = linkedVec.squareDistanceTo(pos);
//            double diff = Math.abs(optimalDist - historyDistance);
//            if (diff < distance) {
//                closestPoint = pos;
//                distance = diff;
//            }
//        }
//
//        // If we found a point closer to our desired distance, move us there
//        if (closestPoint != null)
//            current.setPosition(closestPoint.x, closestPoint.y, closestPoint.z);
//    }

//    /**
//     * Saved the position history of the cart every tick in a Circular Buffer.
//     *
//     * @param cart EntityMinecart
//     */
//    private void savePosition(EntityMinecart cart) {
//        CircularVec3Queue myHistory = history.get(cart);
//        if (myHistory == null) {
//            myHistory = new CircularVec3Queue(TICK_HISTORY);
//            history.put(cart, myHistory);
//        }
//        myHistory.add(cart.posX, cart.posY, cart.posZ);
//    }

    /**
     * This is our entry point, its triggered once per tick per cart.
     *
     * @param event MinecartUpdateEvent
     */
    @SubscribeEvent
    public void onMinecartUpdate(MinecartUpdateEvent event) {
        EntityMinecart cart = event.getMinecart();

        // Physics done here
        adjustCart(cart);

//        savePosition(cart);
    }

    public boolean isLaunched(EntityMinecart cart) {
        int launched = cart.getEntityData().getInteger(CartConstants.TAG_LAUNCHED);
        return launched > 0;
    }

    public boolean isOnElevator(EntityMinecart cart) {
        int elevator = cart.getEntityData().getByte(CartConstants.TAG_ELEVATOR);
        return elevator > 0;
    }

    @SubscribeEvent
    public void canMinecartTick(EntityEvent.CanUpdate event) {
        if (event.getEntity() instanceof EntityMinecart) {
            EntityMinecart cart = (EntityMinecart) event.getEntity();
            if (Train.streamCarts(cart).flatMap(Streams.toType(EntityCartWorldspike.class)).anyMatch(EntityCartWorldspike::hasActiveTicket)) {
                event.setCanUpdate(true);
            }
        }
    }

    /**
     * Enforce speed limit on a coupled train
     */
    private void enforceSpeedLimit(EntityMinecart cart) {
        Train.get(cart).ifPresent(train -> {
            if (train.size() >= MIN_COUPLED_LENGTH_SPEED) {
                boolean onCurve = isOnCurve(cart);
                float maxSpeed = onCurve ? MAX_COUPLED_SPEED_CURVE : MAX_COUPLED_SPEED_STRAIGHT;

                // Reduce speed based on train length
                float lengthFactor = Math.max(0.5f, 1.0f - (train.size() - MIN_COUPLED_LENGTH_SPEED) * 0.1f);
                maxSpeed *= lengthFactor;

                // Apply speed limit to all carts in the train
                for (EntityMinecart trainCart : train) {
                    double currentSpeed = Math.sqrt(trainCart.motionX * trainCart.motionX + trainCart.motionZ * trainCart.motionZ);
                    if (currentSpeed > maxSpeed) {
                        double factor = maxSpeed / currentSpeed;
                        trainCart.motionX *= factor;
                        trainCart.motionZ *= factor;
                    }
                }
            }
        });
    }

    /**
     * Check if coupled carts maintain proper order and distance
     */
    private void checkOrderIntegrity(EntityMinecart cart) {
        int timer = orderCheckTimers.getOrDefault(cart, 0);
        if (timer >= ORDER_CHECK_INTERVAL) {
            Train.get(cart).ifPresent(train -> {
                // Check if cart positions maintain order
                for (int i = 0; i < train.size() - 1; i++) {
                    EntityMinecart current = train.get(i);
                    EntityMinecart next = train.get(i + 1);

                    double distance = current.getDistance(next);
                    if (distance < MIN_LINK_DISTANCE || distance > MAX_LINK_DISTANCE * 1.5) {
                        // Order integrity violation detected
                        repairOrder(train, i);
                        break;
                    }
                }
            });
            orderCheckTimers.put(cart, 0);
        } else {
            orderCheckTimers.put(cart, timer + 1);
        }
    }

    /**
     * Repair order integrity by breaking and re-establishing problematic links
     */
    private void repairOrder(Train train, int problematicIndex) {
        if (problematicIndex < train.size() - 1) {
            EntityMinecart cart1 = train.get(problematicIndex);
            EntityMinecart cart2 = train.get(problematicIndex + 1);

            // Temporarily break problematic link
            LinkageManager.INSTANCE.breakLink(cart1, cart2);

            // Re-establish with proper distance
            LinkageManager.INSTANCE.createLink(cart1, cart2);

            // Apply corrective forces
            applyOrderCorrection(cart1, cart2);
        }
    }

    /**
     * Apply corrective forces to maintain proper distance between carts
     */
    private void applyOrderCorrection(EntityMinecart cart1, EntityMinecart cart2) {
        Vec2D cart1Pos = new Vec2D(cart1);
        Vec2D cart2Pos = new Vec2D(cart2);
        Vec2D unit = Vec2D.unit(cart2Pos, cart1Pos);

        // Calculate optimal distance
        float optDist = getOptimalDistance(cart1, cart2);
        double currentDist = cart1.getDistance(cart2);

        // Apply corrective force to maintain distance
        if (currentDist < optDist) {
            // Push carts apart
            double force = (optDist - currentDist) * 0.1;
            double pushX = unit.getX() * force;
            double pushZ = unit.getY() * force;

            cart1.motionX -= pushX;
            cart1.motionZ -= pushZ;
            cart2.motionX += pushX;
            cart2.motionZ += pushZ;
        } else if (currentDist > optDist) {
            // Pull carts together
            double force = (currentDist - optDist) * 0.05;
            double pullX = unit.getX() * force;
            double pullZ = unit.getY() * force;

            cart1.motionX += pullX;
            cart1.motionZ += pullZ;
            cart2.motionX -= pullX;
            cart2.motionZ -= pullZ;
        }
    }

    /**
     * Monitor coupling tension and apply emergency measures if needed
     */
    private void monitorCouplingTension(EntityMinecart cart1, EntityMinecart cart2) {
        double distance = cart1.getDistance(cart2);
        float optDist = getOptimalDistance(cart1, cart2);
        double tension = Math.abs(distance - optDist) / optDist;

        // If tension is too high, apply emergency damping
        if (tension > 0.5) {
            double emergencyDamping = 0.95;
            cart1.motionX *= emergencyDamping;
            cart1.motionZ *= emergencyDamping;
            cart2.motionX *= emergencyDamping;
            cart2.motionZ *= emergencyDamping;

            // Mark for potential link breakage if tension is extreme
            if (tension > 1.0) {
                LinkageManager.INSTANCE.breakLink(cart1, cart2);
                LinkageManager.printDebug("Reason For Broken Link: Excessive coupling tension.");
            }
        }
    }

    /**
     * Progressive derailment detection with emergency response
     */
    private void checkProgressiveDerailment(EntityMinecart cart) {
        // Check if cart is off track
        BlockPos pos = new BlockPos(cart);
        if (!TrackTools.isRailBlockAt(cart.world, pos)) {
            // Increment derailment counter
            int derailCount = cart.getEntityData().getInteger(CartConstants.TAG_DERAIL);
            derailCount++;
            cart.getEntityData().setInteger(CartConstants.TAG_DERAIL, derailCount);

            // Apply emergency stop if derailed too long
            if (derailCount > 10) {
                cart.motionX *= 0.8;
                cart.motionZ *= 0.8;

                // Try to find nearest rail
                BlockPos nearestRail = findNearestRail(cart);
                if (nearestRail != null) {
                    // Apply corrective force towards rail
                    Vec3d direction = new Vec3d(nearestRail).subtract(new Vec3d(cart.posX, cart.posY, cart.posZ));
                    direction = direction.normalize();

                    double force = 0.1;
                    cart.motionX += direction.x * force;
                    cart.motionY += direction.y * force;
                    cart.motionZ += direction.z * force;
                }
            }
        } else {
            // Reset derailment counter when back on track
            cart.getEntityData().setInteger(CartConstants.TAG_DERAIL, 0);
        }
    }

    /**
     * Find the nearest rail block to the cart
     */
    private BlockPos findNearestRail(EntityMinecart cart) {
        BlockPos pos = new BlockPos(cart);
        int searchRadius = 5;

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                BlockPos checkPos = pos.add(x, 0, z);
                if (TrackTools.isRailBlockAt(cart.world, checkPos)) {
                    return checkPos;
                }
            }
        }
        return null;
    }
}
