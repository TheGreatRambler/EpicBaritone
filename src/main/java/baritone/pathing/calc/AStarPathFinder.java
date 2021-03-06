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

package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.movements.MovementFancy;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;
import java.util.ArrayList;
import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

	private final Favoring favoring;
	private final CalculationContext calcContext;

	private int numNodes               = 0;
	private int numMovementsConsidered = 0;
	private int numEmptyChunk          = 0;
	private double minimumImprovement;
	private boolean failing;
	private boolean isFavoring;
	private BetterWorldBorder worldBorder;
	private double actionCost;
	private long hashCode;

	public AStarPathFinder(int startX, int startY, int startZ, Goal goal,
		Favoring favoring, CalculationContext context) {
		super(startX, startY, startZ, goal, context);
		this.favoring    = favoring;
		this.calcContext = context;
	}

	@Override
	protected Optional<IPath> calculate0(
		long primaryTimeout, long failureTimeout) {
		startNode = getNodeAtPosition((double)startX + 0.5, (double)startY,
			(double)startZ + 0.5,
			BetterBlockPos.longHash(
				(double)startX + 0.5, (double)startY, (double)startZ + 0.5));
		startNode.setPreviousMovement(null);
		startNode.cost            = 0;
		startNode.combinedCost    = startNode.estimatedCostToGoal;
		BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
		openSet.insert(startNode);
		double[] bestHeuristicSoFar
			= new double[COEFFICIENTS.length]; // keep track of the best node by
											   // the metric of
											   // (estimatedCostToGoal + cost /
											   // COEFFICIENTS[i])
		for(int i = 0; i < bestHeuristicSoFar.length; i++) {
			bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
			bestSoFar[i]          = startNode;
		}
		MutableMoveResult res = new MutableMoveResult();
		worldBorder = new BetterWorldBorder(calcContext.world.getWorldBorder());
		long startTime   = System.currentTimeMillis();
		boolean slowPath = Baritone.settings().slowPath.value;
		if(slowPath) {
			logDebug("slowPath is on, path timeout will be "
					 + Baritone.settings().slowPathTimeoutMS.value
					 + "ms instead of " + primaryTimeout + "ms");
		}
		long primaryTimeoutTime
			= startTime
			  + (slowPath ? Baritone.settings().slowPathTimeoutMS.value
						  : primaryTimeout);
		long failureTimeoutTime
			= startTime
			  + (slowPath ? Baritone.settings().slowPathTimeoutMS.value
						  : failureTimeout);
		failing                = true;
		numNodes               = 0;
		numMovementsConsidered = 0;
		numEmptyChunk          = 0;
		isFavoring             = !favoring.isEmpty();
		int timeCheckInterval  = 1 << 6;
		int pathingMaxChunkBorderFetch
			= Baritone.settings()
				  .pathingMaxChunkBorderFetch
				  .value; // grab all settings beforehand so that changing
						  // settings during pathing doesn't cause a crash or
						  // unpredictable behavior
		minimumImprovement
			= Baritone.settings().minimumImprovementRepropagation.value
				  ? MIN_IMPROVEMENT
				  : 0;
		Moves[] allMoves = Moves.values();
		while(!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch
			  && !cancelRequested) {
			if((numNodes & (timeCheckInterval - 1))
				== 0) { // only call this once every 64 nodes (about half a
						// millisecond)
				long now = System.currentTimeMillis(); // since nanoTime is slow
													   // on windows (takes many
													   // microseconds)
				if(now - failureTimeoutTime >= 0
					|| (!failing && now - primaryTimeoutTime >= 0)) {
					break;
				}
			}
			if(slowPath) {
				try {
					Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
				} catch(InterruptedException ignored) {
				}
			}
			PathNode currentNode = openSet.removeLowest();
			mostRecentConsidered = currentNode;
			numNodes++;
			if(goal.isInGoal((int)Math.floor(currentNode.x), (int)currentNode.y,
				   (int)Math.floor(currentNode.z))) {
				logDebug("Took " + (System.currentTimeMillis() - startTime)
						 + "ms, " + numMovementsConsidered
						 + " movements considered");
				return Optional.of(new Path(
					startNode, currentNode, numNodes, goal, calcContext));
			}
			for(Moves moves : allMoves) {
				int newX = (int)Math.floor(currentNode.x) + moves.xOffset;
				int newZ = (int)Math.floor(currentNode.z) + moves.zOffset;
				boolean dynamicXZ = moves.dynamicXZ;
				int yOffset       = moves.yOffset;

				if(impossibleLocations(
					   currentNode, newX, newZ, dynamicXZ, yOffset)) {
					continue;
				}

				res.reset();
				// Calculate using the rounded block location
				moves.apply(calcContext, (int)Math.floor(currentNode.x),
					(int)currentNode.y, (int)Math.floor(currentNode.z), res);

				if(badNode(res, currentNode, newX, newZ, dynamicXZ,
					   moves.dynamicY, yOffset, moves)) {
					continue;
				}

				hashCode = BetterBlockPos.longHash(
					(double)res.x + 0.5, (double)res.y, (double)res.z + 0.5);
				if(isFavoring) {
					// see issue #18
					actionCost *= favoring.calculate(hashCode);
				}

				PathNode neighbor = getNodeAtPosition((double)res.x + 0.5,
					(double)res.y, (double)res.z + 0.5, hashCode);
				Movement movement = moves.apply0(
					calcContext, new BetterBlockPos(currentNode.x,
									 currentNode.y, currentNode.z));
				movement.override(res.cost);

				getPathNode(neighbor, currentNode, openSet, bestHeuristicSoFar,
					movement);
			}

			ArrayList<MovementFancy> possibleFancyMovements
				= MovementFancy.getMoves(calcContext.getBaritone(),
					currentNode.movement, calcContext);

			for(MovementFancy movement : possibleFancyMovements) {
				System.out.println(movement.getImplementedJump());

				int yOffset = movement.getDest().y - (int)currentNode.y;

				double x = movement.getDestX();
				double y = (double)movement.getDest().y;
				double z = movement.getDestZ();

				if(impossibleLocations(currentNode, movement.getDest().x,
					   movement.getDest().z, true, yOffset)) {
					continue;
				}

				res.reset();
				res.cost = movement.getCost(calcContext);
				res.x    = movement.getDest().x;
				res.y    = movement.getDest().y;
				res.z    = movement.getDest().z;

				movement.override(res.cost);

				if(badNode(res, currentNode, movement.getDest().x,
					   movement.getDest().z, true, true, yOffset, movement)) {
					continue;
				}

				hashCode = BetterBlockPos.longHash(x, y, z);
				if(isFavoring) {
					// see issue #18
					actionCost *= favoring.calculate(hashCode);
				}

				PathNode neighbor = getNodeAtPosition(x, y, z, hashCode);

				getPathNode(neighbor, currentNode, openSet, bestHeuristicSoFar,
					movement);
			}
		}
		if(cancelRequested) {
			return Optional.empty();
		}
		System.out.println(numMovementsConsidered + " movements considered");
		System.out.println("Open set size: " + openSet.size());
		System.out.println("PathNode map size: " + mapSize());
		System.out.println(
			(int)(numNodes * 1.0
				  / ((System.currentTimeMillis() - startTime) / 1000F))
			+ " nodes per second");
		Optional<IPath> result = bestSoFar(true, numNodes);
		if(result.isPresent()) {
			logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, "
					 + numMovementsConsidered + " movements considered");
		}
		return result;
	}

	private boolean impossibleLocations(PathNode currentNode, int newX,
		int newZ, boolean dynamicXZ, int yOffset) {
		if((newX >> 4 != (int)Math.floor(currentNode.x) >> 4
			   || newZ >> 4 != (int)Math.floor(currentNode.z) >> 4)
			&& !calcContext.isLoaded(newX, newZ)) {
			// Can't path in unloaded chunk
			// only need to check if the destination is a loaded chunk
			// if it's in a different chunk than the start of the
			// movement
			if(!dynamicXZ) { // only increment the counter if the
							 // movement would have gone out of
							 // bounds guaranteed
				numEmptyChunk++;
			}
			return true;
		}
		if(!dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) {
			return true;
		}
		if((int)currentNode.y + yOffset > 256
			|| (int)currentNode.y + yOffset < 0) {
			return true;
		}
		return false;
	}

	private void getPathNode(PathNode neighbor, PathNode currentNode,
		BinaryHeapOpenSet openSet, double[] bestHeuristicSoFar,
		Movement possibleMovement) {
		double tentativeCost = currentNode.cost + actionCost;
		if(neighbor.cost - tentativeCost > minimumImprovement) {
			neighbor.previous = currentNode;
			neighbor.cost     = tentativeCost;
			neighbor.combinedCost
				= tentativeCost + neighbor.estimatedCostToGoal;
			neighbor.setPreviousMovement(possibleMovement);
			if(neighbor.isOpen()) {
				openSet.update(neighbor);
			} else {
				openSet.insert(neighbor); // dont double count, dont insert into
										  // open set if it's already there
			}
			for(int i = 0; i < COEFFICIENTS.length; i++) {
				double heuristic = neighbor.estimatedCostToGoal
								   + neighbor.cost / COEFFICIENTS[i];
				if(bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
					bestHeuristicSoFar[i] = heuristic;
					bestSoFar[i]          = neighbor;
					if(failing
						&& getDistFromStartSq(neighbor)
							   > MIN_DIST_PATH * MIN_DIST_PATH) {
						failing = false;
					}
				}
			}
		}
	}

	private boolean badNode(MutableMoveResult res, PathNode currentNode,
		int newX, int newZ, boolean dynamicXZ, boolean dynamicY, int yOffset,
		Object moves) {
		numMovementsConsidered++;
		actionCost = res.cost;
		if(actionCost >= ActionCosts.COST_INF) {
			return true;
		}
		if(actionCost <= 0 || Double.isNaN(actionCost)) {
			throw new IllegalStateException(
				moves + " calculated implausible cost " + actionCost);
		}
		// check destination after verifying it's not COST_INF -- some
		// movements return a static IMPOSSIBLE object with COST_INF and
		// destination being 0,0,0 to avoid allocating a new result for
		// every failed calculation
		if(dynamicXZ
			&& !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
			return true;
		}
		if(!dynamicXZ && (res.x != newX || res.z != newZ)) {
			throw new IllegalStateException(
				moves + " " + res.x + " " + newX + " " + res.z + " " + newZ);
		}
		if(!dynamicY && res.y != currentNode.y + yOffset) {
			throw new IllegalStateException(
				moves + " " + res.y + " " + (currentNode.y + yOffset));
		}
		return false;
	}
}
