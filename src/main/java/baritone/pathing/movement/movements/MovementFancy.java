/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.concurrent.Immutable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;

public class MovementFancy extends Movement {
	private Jump thisJumpcalculation;

	public MovementFancy(IBaritone baritone, Jump jump) {
		super(baritone,
			new BetterBlockPos(jump.startX, jump.startY, jump.startZ),
			jump.endingPosition, new BetterBlockPos[0], jump.blockToPlace);
		thisJumpcalculation = jump;
	}

	@Override
	public double calculateCost(CalculationContext context) {
		return thisJumpcalculation.cost;
	}

	@Override
	protected Set<BetterBlockPos> calculateValidPositions() {
		// Return valid positions where feet may be
		return thisJumpcalculation.validFeetLocations;
	}

	public Jump getImplementedJump() {
		return thisJumpcalculation;
	}

	public static ArrayList<MovementFancy> getMoves(IBaritone baritone,
		Movement previousMovement, CalculationContext context) {
		double x;
		double y;
		double z;
		double angle;
		double speed;
		boolean firstTick;

		if(previousMovement instanceof MovementFancy) {
			x = ((MovementFancy)previousMovement).getImplementedJump().realX;
			y = ((MovementFancy)previousMovement).getImplementedJump().realY;
			z = ((MovementFancy)previousMovement).getImplementedJump().realZ;
			angle
				= ((MovementFancy)previousMovement).getImplementedJump().angle;
			speed = ((MovementFancy)previousMovement)
						.getImplementedJump()
						.endingSpeed;
			firstTick = true;
		} else if(previousMovement == null) {
			BetterBlockPos playerFeet
				= baritone.getPlayerContext().playerFeet();
			x = ((double)playerFeet.x) + 0.5;
			y = ((double)playerFeet.y) + 0.5;
			z = ((double)playerFeet.z) + 0.5;
			// The player isn't moving, these values don't matter
			angle     = 0.0;
			speed     = 0.0;
			firstTick = false;
		} else {
			BetterBlockPos dest = previousMovement.getDest();
			// Centered on block
			x = ((double)dest.x) + 0.5;
			y = ((double)dest.y) + 0.5;
			z = ((double)dest.z) + 0.5;

			// Calculate angle from destination
			BlockPos directionPos = previousMovement.getDirection();
			angle = Math.atan2(directionPos.getZ(), directionPos.getX());

			speed     = context.canSprint ? 5.612 : 4.317;
			firstTick = false;
		}

		ArrayList<JumpCalculation[]> jumpCalculations
			= getJumpCalculations(angle, speed, firstTick);
		ArrayList<MovementFancy> validMoves
			= getJumpCosts(baritone, context, x, y, z, jumpCalculations);

		return validMoves;
	}

	// Everything below here is static

	private static double angleSubdivisions = 60.0;
	private static double speedSubdivisions = 80.0;

	private static double maxAngle      = Math.PI * 2;
	private static double maxSpeed      = 20.0;
	private static int maxNumberOfTicks = 68;

	private static double angleIncrease = maxAngle / angleSubdivisions;
	private static double speedIncrease = maxSpeed / speedSubdivisions;

	private static class JumpCalculation {
		public double x;
		public double y;
		public double z;
		public int tick;
		public double speedAfter;
		public double relativeAngle;
		public double[] hitboxX;
		public double[] hitboxZ;
	}

	private static class AngleAndSpeed {
		public double angle;
		public double speed;

		public AngleAndSpeed(double angle_, double speed_) {
			angle = angle_;
			speed = speed_;
		}

		@Override
		public boolean equals(Object o) {
			return ((AngleAndSpeed)o).angle == angle
				&& ((AngleAndSpeed)o).speed == speed;
		}

		@Override
		public int hashCode() {
			return Objects.hash(angle, speed);
		}
	}

	private static class Jump {
		public double startX;
		public double startY;
		public double startZ;
		public double angle;
		public BetterBlockPos endingPosition;
		public double realX;
		public double realY;
		public double realZ;
		public double cost;
		public double endingSpeed;
		public Set<BetterBlockPos> validFeetLocations;
		// Used for headhitters
		public BetterBlockPos blockToPlace;
	}

	// Movement multiplier
	private static double M = 1.274;
	// Jump bonus
	private static double J = 0.3274;

	// https://www.mcpk.wiki/wiki/Nonrecursive_Movement_Formulas
	private static double jumpDistance(double speed, int t, boolean firstTick) {
		if(t < 2) {
			return 0;
		} else {
			if(firstTick) {
				return 1.91 * speed + J + ((0.02 * M) / 0.09) * (t - 2)
					+ ((0.6 * Math.pow(0.91, 2)) / 0.09)
						  * (1 - Math.pow(0.91, t - 2))
						  * (speed + (J / 0.91)
							  - ((0.02 * M) / (0.6 * 0.91 * 0.09)));
			} else {
				return 1.546 * speed + J + ((0.02 * M) / 0.09) * (t - 2)
					+ ((0.6 * Math.pow(0.91, 2)) / 0.09)
						  * (1 - Math.pow(0.91, t - 2))
						  * (speed * 0.6 + (J / 0.91)
							  - ((0.02 * M) / (0.6 * 0.91 * 0.09)));
			}
		}
	}

	private static double jumpSpeed(double speed, int t, boolean firstTick) {
		if(t < 2) {
			return speed;
		} else {
			if(firstTick) {
				return ((0.02 * M) / (0.09))
					+ 0.6 * Math.pow(0.91, t)
						  * (speed + (J / 0.91)
							  - ((0.02 * M) / 0.6 * 0.91 * 0.09));
			} else {
				return ((0.02 * M) / (0.09))
					+ 0.6 * Math.pow(0.91, t)
						  * (speed * 0.6 + (J / 0.91)
							  - ((0.02 * M) / 0.6 * 0.91 * 0.09));
			}
		}
	}

	private static double jumpHeight(int t) {
		if(t == 0)
			return 0;
		else
			return 217 * (1 - Math.pow(0.98, t)) - 3.92 * t;
	}

	private static ArrayList<BetterBlockPos> getPlayerBlockPosition(
		JumpCalculation calc, double playerX, double playerY, double playerZ,
		double playerFeetOffset) {

		ArrayList<BetterBlockPos> positions = new ArrayList<>();
		for(int i = 0; i < calc.hitboxX.length; i++) {
			BetterBlockPos blockAtThisPoint = new BetterBlockPos(
				calc.hitboxX[i] + playerX, playerY + calc.y + playerFeetOffset,
				calc.hitboxZ[i] + playerZ);

			if(!positions.contains(blockAtThisPoint)) {
				positions.add(blockAtThisPoint);
			}
		}

		return positions;
	}

	private static HashMap<AngleAndSpeed, JumpCalculation[]>
		jumpCalculationsSpecificFirstTick;
	private static HashMap<AngleAndSpeed, JumpCalculation[]>
		jumpCalculationsSpecificNotFirstTick;

	static {
		// Obtain these lookup tables statically
		getJumpCalculations();
	}

	private static void getJumpCalculations() {
		// Obtain the lookup tables statically
		HashMap<AngleAndSpeed, JumpCalculation[]> calcsFirstTick
			= new HashMap<>();
		HashMap<AngleAndSpeed, JumpCalculation[]> calcsNotFirstTick
			= new HashMap<>();
		// https://www.mcpk.wiki/wiki/Tiers
		// The exact equation used to calculate jumps in minecraft 1.9+
		// https://www.mcpk.wiki/wiki/Nonrecursive_Movement_Formulas
		// Also some tick counts here:
		// https://www.reddit.com/r/Minecraft/comments/hlmjqt/doing_a_six_block_jump_is_impossible_as_per_116/
		// Source code might be better
		// https://www.mcpk.wiki/wiki/SourceCode:EntityLivingBase
		// https://www.mcpk.wiki/wiki/Vertical_Movement_Formulas

		// 158400 total subdivisions, a lot of calculations
		for(double angle = 0; angle < maxAngle; angle += angleIncrease) {
			for(double initialSpeed = 0; initialSpeed < maxSpeed;
				initialSpeed += speedIncrease) {
				for(boolean isFirstTick : new Boolean[] { true, false }) {
					AngleAndSpeed angleAndSpeed
						= new AngleAndSpeed(angle, initialSpeed);
					JumpCalculation[] tickCalculations
						= new JumpCalculation[maxNumberOfTicks];

					for(int tick = 0; tick <= maxNumberOfTicks; tick++) {
						// https://www.mcpk.wiki/w/index.php?title=Horizontal_Movement_Formulas
						JumpCalculation calc = new JumpCalculation();
						double speedAfter
							= jumpSpeed(initialSpeed, tick, isFirstTick);
						calc.speedAfter    = speedAfter;
						calc.tick          = tick;
						calc.relativeAngle = angle;
						double jumpDistance
							= jumpDistance(initialSpeed, tick, isFirstTick);
						double jumpFall1   = jumpHeight(tick);
						double offsetX     = Math.sin(angle) * jumpDistance;
						double offsetZ     = Math.cos(angle) * jumpDistance;
						calc.x             = offsetX;
						calc.y             = jumpFall1;
						calc.z             = offsetZ;
						calc.relativeAngle = angle;

						calc.hitboxX = new double[12];
						calc.hitboxZ = new double[12];

						// Use the player hitbox to get blocks
						// passed through. Calculate for 16 points:
						// *-*-*-*
						// | | ^
						// * * |
						// | X | 0.6
						// * * |
						// | | v
						// *-*-*-*
						// <- 0.6 ->

						// To use this function to determine if the blocks in
						// front of the feet are empty (optimal sprint jumping
						// will leave space just before another jump), just use
						// this function but add 1 to jumpDistance

						// The uses of this function:
						// - All of [playerFeetOffset=(0,1,2*),jumpDistance+=0]
						// must be empty
						// - One of [playerFeetOffset=(-1)] must be solid
						// - All of [playerFeetOffset=(0,1,2*),jumpDistance+=1]
						// should be empty

						// * If the decimal of y is greater than 0.2,
						// playerFeetOffset must also check for
						// playerFeetOffset=2 (player can be within 3 blocks
						// vertically)

						int index                       = 0;
						final double[][] pointsToRotate = { { -0.3, 0.3 },
							{ -0.1, 0.3 }, { 0.1, 0.3 }, { 0.3, 0.3 },
							{ 0.3, 0.1 }, { 0.3, -0.1 }, { 0.3, -0.3 },
							{ 0.1, -0.3 }, { -0.1, -0.3 }, { -0.3, -0.3 },
							{ -0.3, -0.1 }, { -0.3, 0.1 } };
						for(double[] point : pointsToRotate) {
							// https://gamedev.stackexchange.com/a/86784
							// Rotate the point at this angle,
							// square origin is at 0,0
							double relativeX = point[0] * Math.cos(angle)
											   - point[1] * Math.sin(angle);
							double relativeZ = point[0] * Math.sin(angle)
											   + point[1] * Math.cos(angle);
							// Correct for offset
							double correctedX = offsetX + relativeX;
							double correctedZ = offsetZ + relativeZ;

							calc.hitboxX[index] = correctedX;
							calc.hitboxZ[index] = correctedZ;

							index++;
						}

						tickCalculations[calc.tick] = calc;
					}

					if(isFirstTick) {
						calcsFirstTick.put(angleAndSpeed, tickCalculations);
					} else {
						calcsNotFirstTick.put(angleAndSpeed, tickCalculations);
					}
				}
			}
		}

		jumpCalculationsSpecificFirstTick    = calcsFirstTick;
		jumpCalculationsSpecificNotFirstTick = calcsNotFirstTick;
	}

	public static ArrayList<JumpCalculation[]> getJumpCalculations(
		double angle, double speed, boolean firstTick) {
		// Using the current location, angle and speed of the player, find the
		// possible jumps (and every tick of those possible jumps)
		// Every returned calculation is an approximation
		angle = Math.floor(angle / angleIncrease) * angleIncrease;
		ArrayList<JumpCalculation[]> calcs = new ArrayList<>();
		// Will loop exactly as many as angleSubdivisions
		for(double angleOffset = 0; angleOffset < maxAngle;
			angleOffset += angleIncrease) {
			// Make sure angle is in [0,maxAngle) range
			double adjustedAngle = (angle + angleOffset) % maxAngle;
			double adjustedSpeed = Math.cos(angleOffset) * speed;
			// Round out the speed as well
			adjustedSpeed
				= Math.floor(adjustedSpeed / speedIncrease) * speedIncrease;
			AngleAndSpeed angleAndSpeed
				= new AngleAndSpeed(adjustedAngle, adjustedSpeed);
			JumpCalculation[] calc
				= firstTick
					  ? jumpCalculationsSpecificFirstTick.get(angleAndSpeed)
					  : jumpCalculationsSpecificNotFirstTick.get(angleAndSpeed);
			calcs.add(calc);
		}
		return calcs;
	}

	private static ArrayList<MovementFancy> getJumpCosts(IBaritone baritone,
		CalculationContext context, double x, double y, double z,
		ArrayList<JumpCalculation[]> calcs) {
		ArrayList<MovementFancy> costsArray = new ArrayList<>();
		for(JumpCalculation[] jumpCalculations : calcs) {
			double lastY                                      = 0;
			int currentTick                                   = 0;
			final double playerHeight                         = 1.8;
			Set<BetterBlockPos> validFeetLocationsForThisJump = new HashSet<>();
			for(JumpCalculation tickCalculation : jumpCalculations) {
				// 0th tick is unimportant
				if(currentTick == 0) {
					currentTick++;
					continue;
				}

				boolean isDescending = false;
				if(lastY > tickCalculation.y) {
					// Descending now
					isDescending = true;
				}

				// private class Jump {
				// public double angle;
				// public BetterBlockPos endingPosition;
				// public double fallHeight;
				// public double realX;
				// public double realZ;
				// public double cost;
				// }

				double playerX    = x + tickCalculation.x;
				double playerY    = y + tickCalculation.y;
				double playerZ    = z + tickCalculation.z;
				double fallHeight = -tickCalculation.y;

				validFeetLocationsForThisJump.add(
					new BetterBlockPos(playerX, playerY, playerZ));

				if(isDescending) {
					// Check all non ground tiles, as they have to be empty for
					// the player to pass through them
					boolean allEmpty
						= getPlayerBlockPosition(
							  tickCalculation, playerX, playerY, playerZ, 1.0)
							  .stream()
							  .allMatch(pos
								  -> MovementHelper.fullyPassable(
									  context, pos.x, pos.y, pos.z))
						  && getPlayerBlockPosition(tickCalculation, playerX,
							  playerY, playerZ, playerHeight)
								 .stream()
								 .allMatch(pos
									 -> MovementHelper.fullyPassable(
										 context, pos.x, pos.y, pos.z));
					if(allEmpty) {
						// Get ground tiles
						Stream<BetterBlockPos> groundBlocks
							= getPlayerBlockPosition(
								tickCalculation, playerX, playerY, playerZ, 0.0)
								  .stream();
						// Check if the ground has arrived
						if(Math.ceil(playerY) != Math.ceil(playerY)) {
							// Check if any can be landed on
							if(groundBlocks.anyMatch(pos
								   -> MovementHelper.canWalkOn(
									   context.bsi, pos.x, pos.y, pos.z))) {
								// This is a possible spot, but break from here
								Jump jump  = new Jump();
								jump.angle = tickCalculation.relativeAngle;
								jump.cost  = currentTick + context.jumpPenalty
											+ ActionCosts.FALL_N_BLOCKS_COST[(
												int)fallHeight];
								jump.endingPosition = new BetterBlockPos(
									playerX, playerY, playerZ);
								jump.realX       = playerX;
								jump.realY       = playerY;
								jump.realZ       = playerZ;
								jump.endingSpeed = tickCalculation.speedAfter;
								jump.validFeetLocations = new HashSet<>(
									validFeetLocationsForThisJump);
								jump.blockToPlace = null;
								jump.startX       = x;
								jump.startY       = y;
								jump.startZ       = z;
								costsArray.add(
									new MovementFancy(baritone, jump));

								break;
							} else {
								BetterBlockPos placeableBlock
									= groundBlocks
										  .filter(pos -> {
											  for(int i = 0; i < 5; i++) {
												  int againstX
													  = pos.x
														+ Movement
															  .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP
																  [i]
															  .getXOffset();
												  int againstY
													  = pos.y
														+ Movement
															  .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP
																  [i]
															  .getYOffset();
												  int againstZ
													  = pos.z
														+ Movement
															  .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP
																  [i]
															  .getZOffset();
												  // if(againstX == x + xDiff *
												  // 3
												  // && againstZ
												  // == z + zDiff * 3) { // we
												  // can't
												  // // turn
												  // // around
												  // // that
												  // fast return false;
												  // }
												  if(MovementHelper
														  .canPlaceAgainst(
															  context.bsi,
															  againstX,
															  againstY,
															  againstZ)) {
													  return true;
												  }
											  }

											  return false;
										  })
										  .findFirst()
										  .orElse(null);

								// Check if any block can be placed to recover
								// the player
								if(placeableBlock != null) {
									// Block can be placed. Won't break, as the
									// player can choose not to place blocks and
									// continue falling instead
									Jump jump  = new Jump();
									jump.angle = tickCalculation.relativeAngle;
									jump.cost
										= currentTick + context.jumpPenalty
										  + ActionCosts.FALL_N_BLOCKS_COST[(
											  int)fallHeight]
										  + context.costOfPlacingAt(
											  0, 70, 0, null);
									jump.endingPosition = new BetterBlockPos(
										playerX, playerY, playerZ);
									jump.realX = playerX;
									jump.realY = playerY;
									jump.realZ = playerZ;
									jump.endingSpeed
										= tickCalculation.speedAfter;
									jump.validFeetLocations = new HashSet<>(
										validFeetLocationsForThisJump);
									jump.blockToPlace = placeableBlock;
									jump.startX       = x;
									jump.startY       = y;
									jump.startZ       = z;
									costsArray.add(
										new MovementFancy(baritone, jump));
								}
							}
						} else {
							// Check just in case
							// Player may drift into next block
							if(!groundBlocks.allMatch(pos
								   -> MovementHelper.fullyPassable(
									   context, pos.x, pos.y, pos.z))) {
								// Impossible to calculate from
								// here, break execution
								break;
							}
						}
					} else {
						// Impossible to calculate from here, break execution
						break;
					}
				} else {
					// Check all blocks that should be air
					boolean allEmpty
						= getPlayerBlockPosition(
							  tickCalculation, playerX, playerY, playerZ, 0.0)
							  .stream()
							  .allMatch(pos
								  -> MovementHelper.fullyPassable(
									  context, pos.x, pos.y, pos.z))
						  && getPlayerBlockPosition(
							  tickCalculation, playerX, playerY, playerZ, 1.0)
								 .stream()
								 .allMatch(pos
									 -> MovementHelper.fullyPassable(
										 context, pos.x, pos.y, pos.z));
					if(allEmpty) {
						// Check head blocks for headhitters
						Stream<BetterBlockPos> headhitterLocations
							= getPlayerBlockPosition(tickCalculation, playerX,
								playerY, playerZ, playerHeight)
								  .stream();
						if(currentTick == 1) {
							// Headhitters can only be used on frame 2
							// For 2 block ceilings
							// Entire jump is 2 frames long
							double nextFrameX = x + jumpCalculations[2].x;
							double nextFrameZ = z + jumpCalculations[2].z;
							// Look ahead of time for headhitter specifically
							boolean allEmptyNextFrame
								= getPlayerBlockPosition(tickCalculation,
									  nextFrameX, y, nextFrameZ, 1.0)
									  .stream()
									  .allMatch(pos
										  -> MovementHelper.fullyPassable(
											  context, pos.x, pos.y, pos.z))
								  && getPlayerBlockPosition(tickCalculation,
									  nextFrameX, y, nextFrameZ, playerHeight)
										 .stream()
										 .allMatch(pos
											 -> MovementHelper.fullyPassable(
												 context, pos.x, pos.y, pos.z));
							if(allEmptyNextFrame) {
								// Get ground tiles
								Stream<BetterBlockPos> groundBlocks
									= getPlayerBlockPosition(tickCalculation,
										nextFrameX, y, nextFrameZ, 0.0)
										  .stream();
								// Check if any can be landed on
								if(groundBlocks.anyMatch(pos
									   -> MovementHelper.canWalkOn(
										   context.bsi, pos.x, pos.y, pos.z))) {
									// This is a possible spot, but break from
									// here. Headhitter jump signals end of jump
									Jump jump  = new Jump();
									jump.angle = tickCalculation.relativeAngle;
									jump.cost  = 2.0 + context.jumpPenalty;
									jump.endingPosition = new BetterBlockPos(
										nextFrameX, playerY, nextFrameZ);
									jump.realX = nextFrameX;
									jump.realY = playerY;
									jump.realZ = nextFrameZ;
									jump.endingSpeed
										= jumpCalculations[2].speedAfter;
									jump.validFeetLocations = ImmutableSet.of(
										new BetterBlockPos(
											jumpCalculations[1].x,
											jumpCalculations[1].y,
											jumpCalculations[1].z),
										new BetterBlockPos(
											jumpCalculations[2].x,
											jumpCalculations[2].y,
											jumpCalculations[2].z));
									jump.blockToPlace = null;
									jump.startX       = x;
									jump.startY       = y;
									jump.startZ       = z;
									costsArray.add(
										new MovementFancy(baritone, jump));

									break;
								} else {
									BetterBlockPos placeableBlock
										= groundBlocks
											  .filter(pos -> {
												  for(int i = 0; i < 5; i++) {
													  int againstX
														  = pos.x
															+ Movement
																  .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP
																	  [i]
																  .getXOffset();
													  int againstY
														  = pos.y
															+ Movement
																  .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP
																	  [i]
																  .getYOffset();
													  int againstZ
														  = pos.z
															+ Movement
																  .HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP
																	  [i]
																  .getZOffset();
													  // if(againstX == x +
													  // xDiff * 3
													  // && againstZ
													  // == z + zDiff * 3) { //
													  // we
													  // //
													  // can't
													  // //
													  // turn
													  // //
													  // around
													  // //
													  // that
													  // //
													  // fast return false;
													  // }
													  if(MovementHelper
															  .canPlaceAgainst(
																  context.bsi,
																  againstX,
																  againstY,
																  againstZ)) {
														  return true;
													  }
												  }

												  return false;
											  })
											  .findFirst()
											  .orElse(null);

									// Check if any block can be placed to
									// recover the player
									if(placeableBlock != null) {
										// Block can be placed
										// Technically can keep calculating from
										// here, but I don't want to bother
										// calculating falling after hitting a
										// block on the head
										Jump jump = new Jump();
										jump.angle
											= tickCalculation.relativeAngle;
										jump.cost = 2.0 + context.jumpPenalty
													+ context.costOfPlacingAt(
														0, 70, 0, null);
										jump.endingPosition
											= new BetterBlockPos(nextFrameX,
												playerY, nextFrameZ);
										jump.fallHeight = 0;
										jump.realX      = nextFrameX;
										jump.realY      = playerY;
										jump.realZ      = nextFrameZ;
										jump.endingSpeed
											= jumpCalculations[2].speedAfter;
										jump.validFeetLocations
											= ImmutableSet.of(
												new BetterBlockPos(
													jumpCalculations[1].x,
													jumpCalculations[1].y,
													jumpCalculations[1].z),
												new BetterBlockPos(
													jumpCalculations[2].x,
													jumpCalculations[2].y,
													jumpCalculations[2].z));
										jump.blockToPlace = placeableBlock;
										jump.startX       = x;
										jump.startY       = y;
										jump.startZ       = z;
										costsArray.add(
											new MovementFancy(baritone, jump));

										break;
									}
								}
							} else {
								// Since next frame wont work, cancel
								break;
							}

						} else {
							// Headhitters CAN be used other frames, but I'm not
							// smart enough to figure it out
							if(!headhitterLocations.allMatch(pos
								   -> MovementHelper.fullyPassable(
									   context, pos.x, pos.y, pos.z))) {
								// These blocks being solid any other time is
								// useless to us
								break;
							}
						}
					} else {
						// Impossible to calculate from here, break execution
						break;
					}
				}

				currentTick++;
			}
		}

		return costsArray;
	}
}