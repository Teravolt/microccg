package ai.ccg;

import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.WorkerRush;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.ParameterSpecification;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import rts.*;
import rts.units.Unit;
import rts.units.UnitTypeTable;
import util.Pair;
import util.XMLWriter;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by pavankantharaju on 11/16/17.
 */

public class MicroCCG_v2 extends AbstractionLayerAI {

    /* NOTE: All loggers must be commented out prior to tournament */
    private Logger LOGGER = Logger.getLogger(MicroCCG_v2.class.getName());
    private Level level = Level.SEVERE;

    private static final int LANGUAGE_JSON = 1;
    private UnitTypeTable utt;

    /* Networking code */
    private String server_address = "127.0.0.1";
    private int server_port = 9898;
    private Socket socket = null;
    private BufferedReader in_pipe = null;
    private PrintWriter out_pipe = null;

    /* Lex Adversarial Generation executable + arguments */
    public final String planner_executable = "./src/ai/ccg/microCCG";
    public final String plan_file = "src/ai/ccg/domain.microRTS.cig2018.planning.no.attack.elx";

    /* For testing purposes */
    //public final String plan_file = "src/ai/ccg/domain.microRTS.cig2018.planning.nomove.testing.elx";

    private Process p;

    private HashMap<String, ArrayList<Unit>> unit_table;
    private ArrayList<Unit> all_usable_player_units;
    private ArrayList<Unit> player_worker_units;
    private ArrayList<Unit> all_available_resources;
    private ArrayList<Unit> all_player_base_units;
    private Random rand;

    /* For parameter policy */
    private Unit player_construction_unit = null;
    private Unit opponent_construction_unit = null;

    public GameState actual_current_game_state;

    private LinkedHashMap<Integer,GameState> id_to_game_state;
    private int current_id;

    private String [] unit_attack_priority = { "Ranged", "Light", "Heavy", "Worker", "Barracks", "Base" };

    private boolean no_planning_required = false;
    private WorkerRush worker_rush;

    LinkedHashMap< Integer, ArrayList< Pair_CCG< Integer,Integer > > > player_to_barrack_locations;

    private int MAP_WIDTH;
    private int MAP_HEIGHT;
    private int initial_capacity = 1000;

    public MicroCCG_v2(UnitTypeTable a_utt) {
        super(new AStarPathFinding(), 100, -1);
        worker_rush = new WorkerRush(a_utt);

        LOGGER.setLevel(level);
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>(initial_capacity);
        all_usable_player_units = new ArrayList<Unit>(initial_capacity);
        player_worker_units = new ArrayList<Unit>(initial_capacity);
        all_available_resources = new ArrayList<Unit>(initial_capacity);
        all_player_base_units = new ArrayList<Unit>(initial_capacity);
        rand = new Random(System.currentTimeMillis());
        id_to_game_state = new LinkedHashMap<Integer,GameState>(initial_capacity);
        current_id = 0;
    }

    public MicroCCG_v2(int mt, int mi, UnitTypeTable a_utt) {
        super(new AStarPathFinding(), mt, mi);
        worker_rush = new WorkerRush(a_utt);

        LOGGER.setLevel(level);
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>(initial_capacity);
        all_usable_player_units = new ArrayList<Unit>(initial_capacity);
        player_worker_units = new ArrayList<Unit>(initial_capacity);
        all_available_resources = new ArrayList<Unit>(initial_capacity);
        all_player_base_units = new ArrayList<Unit>(initial_capacity);
        rand = new Random(System.currentTimeMillis());

        id_to_game_state = new LinkedHashMap<Integer,GameState>(initial_capacity);
        current_id = 0;
    }


    public MicroCCG_v2(int mt, int mi, String a_sa, int a_port, UnitTypeTable a_utt) {
        super(new AStarPathFinding(), mt, mi);
        worker_rush = new WorkerRush(a_utt);

        LOGGER.setLevel(level);
        server_address = a_sa;
        server_port = a_port;
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>(initial_capacity);
        all_usable_player_units = new ArrayList<Unit>(initial_capacity);
        player_worker_units = new ArrayList<Unit>(initial_capacity);
        all_available_resources = new ArrayList<Unit>(initial_capacity);
        all_player_base_units = new ArrayList<Unit>(initial_capacity);
        rand = new Random(System.currentTimeMillis());

        id_to_game_state = new LinkedHashMap<Integer,GameState>(initial_capacity);
        current_id = 0;
    }

    public void setupEnvironment() {
        try {
            ProcessBuilder build = new ProcessBuilder(planner_executable,Integer.toString(server_port),plan_file);
            System.out.println("Starting the planner");
            build.inheritIO();
            p = build.start();
            Thread.sleep(1000);

            connectToServer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void connectToServer() throws Exception {

        socket = new Socket(server_address, server_port);
        socket.setSoTimeout(2000); /* 1 second before timeout (on the off-chance the agent faults) */

        in_pipe = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out_pipe = new PrintWriter(socket.getOutputStream(), true);
    }

    @Override
    public void reset() {
        if ( no_planning_required ) {
            /* Don't execute anything */
            return;
        }
        /* Send over budget and unit type table */
        try {
            setupEnvironment();

            // wait for ack:
            in_pipe.readLine();

            // set the game parameters:
            out_pipe.append("budget " + (TIME_BUDGET-5) + "," + ITERATIONS_BUDGET + "\n");
            out_pipe.flush();

            // wait for ack:
            in_pipe.readLine();

            out_pipe.append("utt: ");
            StringWriter buffer = new StringWriter();
            XMLWriter w = new XMLWriter(buffer, " ");
            utt.toxml(w);
            buffer.append("\n");
            out_pipe.write(buffer.toString());
            out_pipe.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendGameState(GameState gs, boolean send_game_over) {
        /* Instead of sending the game state, send an ID corresponding to its location in the hashmap */
        try {
            /* Wait for message */
            if (gs == null) {
                /* GameState failed to update */
                in_pipe.readLine();
                out_pipe.write("failed\n");
                out_pipe.flush();
                return;
            }

            in_pipe.readLine();

            if (gs.gameover() && send_game_over) {
                /* Don't send game over when executing */
                out_pipe.write("gameover\n");
                out_pipe.flush();
            } else {
                id_to_game_state.put(current_id,gs);
                out_pipe.write(Integer.toString(current_id));
                out_pipe.flush();
                current_id++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public PlayerAction prepareActionNonAbstract(String action_name, GameState gs, int player, String min_max) {
        /* We might conflict for resources when creating new units */
        /* Use this cloned game state for resolving conflicts in movement and construction */
        GameState cloned_gs = gs.clone();

        unit_table.clear();
        for (Unit u : gs.getUnits()) {
            ArrayList<Unit> tmp;
            if (unit_table.containsKey(u.getType().name)) {
                tmp = unit_table.get(u.getType().name);
                tmp.add(u);
                unit_table.replace(u.getType().name, tmp);
            } else {
                tmp = new ArrayList<Unit>();
                tmp.add(u);
                unit_table.put(u.getType().name, tmp);
            }
        }

        PlayerAction ret_action = new PlayerAction();

        all_usable_player_units.clear(); /* All units */
        for (String u_type : unit_table.keySet()) {
            for (Unit u : unit_table.get(u_type)) {
                UnitAction uact = gs.getUnitAction(u);
                if (u.getPlayer() != -1 && u.getPlayer() == player && (uact == null || uact.getType() == UnitAction.TYPE_NONE)) {
                    all_usable_player_units.add(u);
                }
            }
        }

        player_worker_units.clear();
        for (Unit u : all_usable_player_units) {
            if (u.getType().name.equals("Worker")) {
                player_worker_units.add(u);
            }
        }

        /* Always attack opponent units */
        ArrayList<Unit> attack_units = new ArrayList<Unit>();
        for (Unit u : all_usable_player_units) {
            if ( !u.getType().name.equals("Worker") && !u.getType().name.equals("Barracks") && !u.getType().name.equals("Base") ) {
                attack_units.add(u);
            }
        }

        /* Attacking behavior:
         *  Behavior is defined as follows:
         *      Find the highest priority unit that is the closest. barracks > bases > offensive > workers
         * */
        if ( !attack_units.isEmpty() ) {
            for (Unit attacking_unit : attack_units) {
                /* Check if we are in exactly attacking range of the enemy */
                int attacking_x = attacking_unit.getX();
                int attacking_y = attacking_unit.getY();

                Unit closest_enemy_unit = null;
                double min_dist = Math.pow(2, 10);
                for (String u_type : unit_attack_priority) {
                    if (unit_table.containsKey(u_type)) {
                        for (Unit u : unit_table.get(u_type)) {
                            if (u.getPlayer() != -1 && u.getPlayer() == (1 - player)) {
                                /* Manhattan distance */
                                double dist = Math.abs(attacking_x - u.getX()) + Math.abs(attacking_y - u.getY());
                                if (dist < min_dist && pf.pathToPositionInRangeExists(attacking_unit,
                                        u.getX() + u.getY() * MAP_WIDTH, attacking_unit.getAttackRange(), cloned_gs, null)) {
                                    closest_enemy_unit = u;
                                    min_dist = dist;
                                }
                            }
                        }
                    }
                    if (closest_enemy_unit != null) {
                        break;
                    }
                }

                if (closest_enemy_unit == null) {
                    /* This technically means that all enemy units are dead...and we won */
                    return ret_action;
                }

                int enemy_x = closest_enemy_unit.getX();
                int enemy_y = closest_enemy_unit.getY();
                int range = attacking_unit.getAttackRange();

                boolean run_away = false;
                if ( attacking_unit.getType().name.equals("Ranged") ) {
                    LinkedList<Unit> units_too_close = (LinkedList<Unit>)gs.getPhysicalGameState().getUnitsAround(attacking_x, attacking_y,range-1);
                    if ( units_too_close.contains(attacking_unit) ) {
                        units_too_close.remove(attacking_unit);
                    }
                    for ( Unit u : units_too_close ) {
                        if ( u.getPlayer() == (1-player) ) {
                            /* Run away */
                            run_away = true;
                            break;
                        }
                    }
                }

                if ( !run_away ) {
                    /* If we're too far, move */
                    UnitAction test_action = new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, enemy_x, enemy_y);
                    if (attacking_unit.canExecuteAction(test_action, gs)) {
                        ret_action.addUnitAction(attacking_unit, test_action);
                    } else {
                        /* Move towards the unit */
                        UnitAction move = pf.findPathToAdjacentPosition(attacking_unit,
                                enemy_x + enemy_y * MAP_WIDTH, cloned_gs, null);
                        if (move != null) {
                            ret_action.addUnitAction(attacking_unit, move);
                            cloned_gs.issueSafe(ret_action);
                        } else {
                            ArrayList<Integer> random_move = new ArrayList<Integer>();
                            boolean [][] free_map = gs.getAllFree();
                            if ( attacking_x+1 < MAP_WIDTH && free_map[attacking_x+1][attacking_y] ) {
                                random_move.add(UnitAction.DIRECTION_RIGHT);
                            }
                            if ( attacking_y+1 < MAP_HEIGHT && free_map[attacking_x][attacking_y+1] ) {
                                random_move.add(UnitAction.DIRECTION_DOWN);
                            }
                            if ( attacking_x-1 >= 0 && free_map[attacking_x-1][attacking_y] ) {
                                random_move.add(UnitAction.DIRECTION_LEFT);
                            }
                            if ( attacking_y-1 >= 0 && free_map[attacking_x][attacking_y-1] )  {
                                random_move.add(UnitAction.DIRECTION_UP);
                            }
                            if ( !random_move.isEmpty() ) {
                                ret_action.addUnitAction(attacking_unit, new UnitAction(UnitAction.TYPE_MOVE,
                                        random_move.get(rand.nextInt(random_move.size()))));
                                cloned_gs.issueSafe(ret_action);
                            }
                        }
                    }
                } else {
                    /* Run away from the opponent unit */
                    UnitAction move = null;
                    if ( attacking_x - enemy_x < 0 && attacking_x - 1 >= 0 ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_LEFT);
                    }
                    if ( attacking_x - enemy_x > 0 && attacking_x + 1 < MAP_WIDTH ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_RIGHT);
                    }
                    if ( attacking_y - enemy_y < 0 && attacking_y - 1 >= 0 ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_UP);
                    }
                    if ( attacking_y - enemy_y > 0 && attacking_y + 1 < MAP_HEIGHT ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_DOWN);
                    }
                    if ( move != null ) {
                        ret_action.addUnitAction(attacking_unit, move);
                        cloned_gs.issueSafe(ret_action);
                    }
                }
            }
        }

        if ( action_name.contains("produce") ) {
            if (gs.getPlayer(player).getResources() <= 0) {
                /* We don't have enough resources */
                return ret_action;
            }

            /* Get the number of resources that are being used */
            int num_resources_currently_used = 0;
            for (String unit_type : unit_table.keySet()) {
                for (Unit u : unit_table.get(unit_type)) {
                    if (u.getPlayer() == player) {
                        if (gs.getUnitActions().containsKey(u) && gs.getUnitAction(u).getType() == UnitAction.TYPE_PRODUCE) {
                            num_resources_currently_used += gs.getUnitAction(u).getUnitType().cost;
                        }
                    }
                }
            }

            String unit_produced = action_name.substring("produce".length(), action_name.length());
            unit_produced = unit_produced.substring(0, 1).toUpperCase() + unit_produced.substring(1);

            /* If we need to spend more than we can handle, then don't produce */
            if (utt.getUnitType(unit_produced).cost > (gs.getPlayer(player).getResources() - num_resources_currently_used)) {
                return ret_action;
            }
        }

        int rand_num = 0;
        switch (action_name) {
            case "attack":
                /* Should never be here */
                return ret_action;
            case "return":
                /* Get a unit with resources that's closest to a base */
                boolean [][] free_map = cloned_gs.getAllFree();

                all_player_base_units.clear();
                if (unit_table.containsKey("Base")) {
                    for (Unit u : unit_table.get("Base")) {
                        /* Unit is ours and not executing anything */
                        if (u.getPlayer() == player) {
                            all_player_base_units.add(u);
                        }
                    }
                }

                if (all_player_base_units.isEmpty()) {
                    return ret_action;
                }

                /* For all workers that have resources, return them... */

                Unit closest_base = null;

                int direction = -1;
                for (Unit wrkr : player_worker_units) {
                    int x = wrkr.getX();
                    int y = wrkr.getY();
                    if (wrkr.getResources() > 0) {
                        for (Unit u : all_player_base_units) {
                            closest_base = u;
                            if ( (x + 1 == u.getX()) && (y == u.getY()) ) {
                                direction = UnitAction.DIRECTION_RIGHT;
                                closest_base = u;
                                break;
                            }
                            if ( (x == u.getX()) && (y + 1 == u.getY()) ) {
                                direction = UnitAction.DIRECTION_DOWN;
                                closest_base = u;
                                break;
                            }
                            if ( (x - 1 == u.getX()) && (y == u.getY()) ) {
                                direction = UnitAction.DIRECTION_LEFT;
                                closest_base = u;
                                break;
                            }
                            if ( (x == u.getX()) && (y - 1 == u.getY()) )  {
                                direction = UnitAction.DIRECTION_UP;
                                closest_base = u;
                                break;
                            }
                        }

                        if ( direction > -1 ) {
                            ret_action.addUnitAction(wrkr,  new UnitAction(UnitAction.TYPE_RETURN, direction));
                        } else {
                            /* Move towards the unit */
                            UnitAction move = pf.findPathToAdjacentPosition(wrkr,
                                    closest_base.getX() + closest_base.getY() * gs.getPhysicalGameState().getWidth(), cloned_gs, null);
                            if ( move != null ) {
                                ret_action.addUnitAction(wrkr, move);
                                cloned_gs.issueSafe(ret_action);
                            } else {
                                /* Move randomly? */
                                int worker_x = wrkr.getX();
                                int worker_y = wrkr.getY();

                                ArrayList<Integer> random_move = new ArrayList<Integer>();
                                if ( worker_x+1 < MAP_WIDTH && free_map[worker_x+1][worker_y] ) {
                                    random_move.add(UnitAction.DIRECTION_RIGHT);
                                }
                                if ( worker_y+1 < MAP_HEIGHT && free_map[worker_x][worker_y+1] ) {
                                    random_move.add(UnitAction.DIRECTION_DOWN);
                                }
                                if ( worker_x-1 >= 0 && free_map[worker_x-1][worker_y] ) {
                                    random_move.add(UnitAction.DIRECTION_LEFT);
                                }
                                if ( worker_y-1 >= 0 && free_map[worker_x][worker_y-1] )  {
                                    random_move.add(UnitAction.DIRECTION_UP);
                                }
                                if ( !random_move.isEmpty() ) {
                                    ret_action.addUnitAction(wrkr, new UnitAction(UnitAction.TYPE_MOVE,
                                            random_move.get(rand.nextInt(random_move.size()))));
                                    cloned_gs.issueSafe(ret_action);
                                }
                            }
                        }
                    }
                }
                return ret_action;
            case "harvest":
                all_player_base_units.clear();
                if (unit_table.containsKey("Base")) {
                    for (Unit u : unit_table.get("Base")) {
                        if (u.getPlayer() == player) {
                            all_player_base_units.add(u);
                        }
                    }
                }

                /* Requires worker unit, and resources (all have to be reachable) */
                if (player_worker_units.isEmpty() && all_player_base_units.isEmpty() ) {
                    /* Rest in peace */
                    return ret_action;
                }

                all_available_resources.clear();
                if (unit_table.containsKey("Resource")) {
                    for (Unit u : unit_table.get("Resource")) {
                        all_available_resources.add(u);
                    }
                }
                if (all_player_base_units.isEmpty()) {
                    return ret_action;
                }
                /* Find closest resource to the first four workers  */
                for ( int i = 0; i < player_worker_units.size(); i++) {
                    Unit worker_unit = player_worker_units.get(i);
                    if ( !(worker_unit.getResources() == 0) ) {
                            continue;
                    }
                    int worker_x = worker_unit.getX();
                    int worker_y = worker_unit.getY();

                    while ( !all_available_resources.isEmpty() ) {
                        Unit closest_resource = null;
                        double min_dist = Math.pow(2, 10);
                        for (Unit u : all_available_resources) {
                            /* Manhattan distance */
                            double dist = Math.abs(worker_x - u.getX()) + Math.abs(worker_y - u.getY());
                            if (dist < min_dist) {
                                closest_resource = u;
                                min_dist = dist;
                            }
                        }

                        if (closest_resource == null) {
                            /* No more resources */
                            return ret_action;
                        }

                        direction = -1;
                        int closest_resource_x = closest_resource.getX();
                        int closest_resource_y = closest_resource.getY();

                        if ((worker_x + 1 == closest_resource_x) && (worker_y == closest_resource_y)) {
                            direction = UnitAction.DIRECTION_RIGHT;
                        }
                        if ((worker_x == closest_resource_x) && (worker_y + 1 == closest_resource_y)) {
                            direction = UnitAction.DIRECTION_DOWN;
                        }
                        if ((worker_x - 1 == closest_resource_x) && (worker_y == closest_resource_y)) {
                            direction = UnitAction.DIRECTION_LEFT;
                        }
                        if ((worker_x == closest_resource_x) && (worker_y - 1 == closest_resource_y)) {
                            direction = UnitAction.DIRECTION_UP;
                        }

                        if (direction > -1) {
                            ret_action.addUnitAction(worker_unit, new UnitAction(UnitAction.TYPE_HARVEST, direction));
                            break;
                        } else {
                            /* Move towards the resource */
                            UnitAction move = pf.findPathToAdjacentPosition(worker_unit,
                                    closest_resource_x + closest_resource_y * MAP_WIDTH, cloned_gs, null);
                            if (move != null) {
                                ret_action.addUnitAction(worker_unit, move);
                                cloned_gs.issueSafe(ret_action);
                                break;
                            } else {
                                all_available_resources.remove(closest_resource);
                            }
                        }
                    }
                }

                return ret_action;
            case "idle":
                /* Call a random player unit to remain idle */
                if (all_usable_player_units.isEmpty()) {
                    return ret_action;
                }
                rand_num = rand.nextInt(all_usable_player_units.size());
                ret_action.addUnitAction(all_usable_player_units.get(rand_num), new UnitAction(UnitAction.TYPE_NONE));
                return ret_action;
            case "produceworker":
                free_map = cloned_gs.getAllFree();

                String unit_produced = "Worker";
                if ( utt.getUnitType(unit_produced).cost > gs.getPlayer(player).getResources() ) {
                    return ret_action;
                }

                ArrayList<Unit> all_player_workers = new ArrayList<Unit>();
                if ( unit_table.containsKey("Worker") ) {
                    for ( Unit u : unit_table.get("Worker") ) {
                        if ( u.getPlayer() == player ) {
                            all_player_workers.add(u);
                        }
                    }
                }

                /* Assume 12x12 minimum map */
                if ( all_player_workers.size() > 3) {
                    return ret_action;
                }

                /* Produce workers (Going to usually have a single base) */
                ArrayList<Unit> free_bases = new ArrayList<Unit>();
                for (Unit u : all_usable_player_units) {
                    if ( u.getType().name.equals("Base") ) {
                        free_bases.add(u);
                    }
                }

                if (free_bases.size() == 0) {
                    return ret_action;
                }
                rand_num = rand.nextInt(free_bases.size());

                int x = free_bases.get(rand_num).getX();
                int y = free_bases.get(rand_num).getY();

                ArrayList<Integer> free_positions = new ArrayList<Integer>();

                /* Get direction of production (Try to create away from nearby resources?) */
                if (x + 1 < MAP_WIDTH && free_map[x + 1][y] ) {
                    free_positions.add(UnitAction.DIRECTION_RIGHT);
                }
                if (y + 1 < MAP_HEIGHT && free_map[x][y + 1]) {
                    free_positions.add(UnitAction.DIRECTION_DOWN);
                }
                if (x - 1 >= 0 && free_map[x - 1][y] ) {
                    free_positions.add(UnitAction.DIRECTION_LEFT);
                }
                if (y - 1 >= 0 && free_map[x][y - 1]) {
                    free_positions.add(UnitAction.DIRECTION_UP);
                }

                if ( free_positions.isEmpty() ) {
                    return ret_action;
                }

                int rand_num2 = rand.nextInt(free_positions.size());
                ret_action.addUnitAction(free_bases.get(rand_num), new UnitAction(UnitAction.TYPE_PRODUCE, free_positions.get(rand_num2), utt.getUnitType(unit_produced)));
                return ret_action;
            case "producebarracks":
            case "producebase":
                unit_produced = action_name.substring("produce".length(),action_name.length());
                unit_produced = unit_produced.substring(0,1).toUpperCase() + unit_produced.substring(1);

                if ( utt.getUnitType(unit_produced).cost > gs.getPlayer(player).getResources() ) {
                    return ret_action;
                }

                Unit construction_unit = null;
                if ( min_max.equals("min") ) {
                    if ( opponent_construction_unit == null
                            || gs.getUnit(opponent_construction_unit.getID()) == null ) {
                        /* Need to have at least one worker */
                        ArrayList<Unit> tmp = new ArrayList<Unit>();
                        if ( player_worker_units.size() == 0 ) {
                            return ret_action;
                        }
                        for ( Unit u : player_worker_units ) {
                            if ( actual_current_game_state.getUnit(u.getID()) != null ) {
                                tmp.add(u);
                            }
                        }
                        if ( tmp.isEmpty() ) {
                            return ret_action;
                        }
                        rand_num = rand.nextInt(tmp.size());
                        opponent_construction_unit = tmp.get(rand_num);
                    } else {
                        opponent_construction_unit = gs.getUnit(opponent_construction_unit.getID());
                    }
                    /* Unit needs to be updated at each iteration */
                    construction_unit = opponent_construction_unit;
                } else {
                    if ( player_construction_unit == null
                            || gs.getUnit(player_construction_unit.getID()) == null  ) {
                        /* Need to have at least one worker */
                        ArrayList<Unit> tmp = new ArrayList<Unit>();
                        if ( player_worker_units.size() == 0 ) {
                            return ret_action;
                        }
                        for ( Unit u : player_worker_units ) {
                            if ( actual_current_game_state.getUnit(u.getID()) != null ) {
                                tmp.add(u);
                            }
                        }
                        if ( tmp.isEmpty() ) {
                            return ret_action;
                        }
                        rand_num = rand.nextInt(tmp.size());
                        player_construction_unit = tmp.get(rand_num); //player_worker_units.get(rand_num);
                    } else {
                        player_construction_unit = gs.getUnit(player_construction_unit.getID());
                    }
                    /* Unit needs to be updated each iteration */
                    construction_unit = player_construction_unit;
                }

                if ( gs.getUnitAction(construction_unit) != null && gs.getUnitAction(construction_unit).getType() != UnitAction.TYPE_NONE) {
                    return ret_action;
                }

                x = construction_unit.getX();
                y = construction_unit.getY();

                free_positions = new ArrayList<Integer>();
                ArrayList< Pair_CCG< Integer, Integer > > barrack_locations = player_to_barrack_locations.get(player);
                free_map = cloned_gs.getAllFree();

                if ( barrack_locations.isEmpty() ) {
                    if (x + 1 < MAP_WIDTH && free_map[x + 1][y] ) {
                        free_positions.add(UnitAction.DIRECTION_RIGHT);
                    }
                    if (y + 1 < MAP_HEIGHT && free_map[x][y + 1]) {
                        free_positions.add(UnitAction.DIRECTION_DOWN);
                    }
                    if (x - 1 >= 0 && free_map[x - 1][y] ) {
                        free_positions.add(UnitAction.DIRECTION_LEFT);
                    }
                    if (y - 1 >= 0 && free_map[x][y - 1]) {
                        free_positions.add(UnitAction.DIRECTION_UP);
                    }

                    if ( free_positions.isEmpty() ) {
                        return ret_action;
                    }
                    rand_num = rand.nextInt(free_positions.size());
                    ret_action.addUnitAction(construction_unit, new UnitAction(UnitAction.TYPE_PRODUCE, free_positions.get(rand_num), utt.getUnitType(unit_produced)));
                    return ret_action;
                }

                if ( barrack_locations.contains(new Pair_CCG<Integer, Integer>(x+1, y)) && free_map[x+1][y] ) {
                    free_positions.add(UnitAction.DIRECTION_RIGHT);
                }
                if ( barrack_locations.contains(new Pair_CCG<Integer, Integer>(x, y+1)) && free_map[x][y+1] ) {
                    free_positions.add(UnitAction.DIRECTION_DOWN);
                }
                if ( barrack_locations.contains(new Pair_CCG<Integer, Integer>(x-1, y)) && free_map[x-1][y] ) {
                    free_positions.add(UnitAction.DIRECTION_LEFT);
                }
                if ( barrack_locations.contains(new Pair_CCG<Integer, Integer>(x, y-1)) && free_map[x][y-1] ) {
                    free_positions.add(UnitAction.DIRECTION_UP);
                }

                if ( !free_positions.isEmpty() ) {
                    rand_num = rand.nextInt(free_positions.size());
                    ret_action.addUnitAction(construction_unit, new UnitAction(UnitAction.TYPE_PRODUCE, free_positions.get(rand_num), utt.getUnitType(unit_produced)));
                    return ret_action;
                }

                for ( Pair_CCG< Integer, Integer > barrack_location : barrack_locations ) {
                    /* Move unit here */
                    if ( gs.getPhysicalGameState().getUnitAt(barrack_location.m_a, barrack_location.m_b) != null && (gs.getPhysicalGameState().getUnitAt(barrack_location.m_a, barrack_location.m_b).getType().name.equals("Barracks")
                        || gs.getPhysicalGameState().getUnitAt(barrack_location.m_a, barrack_location.m_b).getType().name.equals("Base")) ) {
                        continue;
                    }
                    UnitAction move = pf.findPathToAdjacentPosition(construction_unit,
                            barrack_location.m_a + barrack_location.m_b * MAP_WIDTH, cloned_gs, null);
                    if (move != null) {
                        ret_action.addUnitAction(construction_unit, move);
                        cloned_gs.issueSafe(ret_action);
                        break;
                    }
                }
                return ret_action;
            case "producelight":
            case "produceheavy":
            case "produceranged":
                free_map = cloned_gs.getAllFree();

                unit_produced = action_name.substring("produce".length(),action_name.length());

                /* Upper case the first letter */
                unit_produced = unit_produced.substring(0,1).toUpperCase() + unit_produced.substring(1);

                if ( utt.getUnitType(unit_produced).cost > gs.getPlayer(player).getResources() ) {
                    return ret_action;
                }
                free_positions = new ArrayList<Integer>();

                ArrayList<Unit> available_barracks = new ArrayList<Unit>();
                for ( Unit u: all_usable_player_units ) {
                    if ( u.getType().name.equals("Barracks") ) {
                        available_barracks.add(u);
                    }
                }

                if ( available_barracks.isEmpty() ) {
                    return ret_action;
                }

                rand_num = rand.nextInt(available_barracks.size());

                x = available_barracks.get(rand_num).getX();
                y = available_barracks.get(rand_num).getY();

                /* Get direction of production */
                if (x + 1 < MAP_WIDTH && free_map[x + 1][y]) {
                    free_positions.add(UnitAction.DIRECTION_RIGHT);
                }
                if (y + 1 < MAP_HEIGHT && free_map[x][y + 1]) {
                    free_positions.add(UnitAction.DIRECTION_DOWN);
                }
                if (x - 1 >= 0 && free_map[x - 1][y]) {
                    free_positions.add(UnitAction.DIRECTION_LEFT);
                }
                if (y - 1 >= 0 && free_map[x][y - 1]) {
                    free_positions.add(UnitAction.DIRECTION_UP);
                }

                if ( free_positions.isEmpty() ) {
                    return ret_action;
                }

                rand_num2 = rand.nextInt(free_positions.size());

                ret_action.addUnitAction(available_barracks.get(rand_num), new UnitAction(UnitAction.TYPE_PRODUCE, free_positions.get(rand_num2),utt.getUnitType(unit_produced)));
                return ret_action;
        }
        LOGGER.severe("This action is illegal: " + action_name);
        return null;
    }

    public void getNextAction(int pid) {
        try {
            String game_state_id = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            String action_name = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            String min_max_player = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            int player = min_max_player.equals("max") ? pid : (1 - pid);

            GameState current_game_state = id_to_game_state.get(Integer.parseInt(game_state_id)).clone();
            PlayerAction pa = prepareActionNonAbstract(action_name, current_game_state, player, min_max_player);

            StringWriter buffer = new StringWriter();
            pa.toJSON(buffer);
            String tmp_action = buffer.toString();

            in_pipe.readLine();
            out_pipe.write(tmp_action + "\n");
            out_pipe.flush();

        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    public void execute() {
        try {
            String game_state_id = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            GameState current_game_state = id_to_game_state.get(Integer.parseInt(game_state_id)).clone();

            PlayerAction max_actions = getActionsFromCPP(current_game_state);
            PlayerAction min_actions = getActionsFromCPP(current_game_state);

            LOGGER.fine("Actions for max to execute: " + max_actions);
            LOGGER.fine("Actions for min to execute: " + min_actions);

            GameState next_game_state = null;
            try {
                current_game_state.issueSafe(max_actions);
                current_game_state.issueSafe(min_actions);
                current_game_state.cycle();
                next_game_state = current_game_state;
            } catch (Exception e) {
                /* Player action or game state inconsistent...should never be here. */
                LOGGER.severe("Player action or game state was inconsistent. This should never happen!");

                in_pipe.readLine();
                out_pipe.write("failed\n");
                out_pipe.flush();

                next_game_state = null;
            }

            sendGameState(next_game_state,false);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public GameState simulate(GameState gs) {
        boolean game_over = false;
        while ( !checkUnitActivity(gs,-1) ) {
            game_over = gs.cycle();
            if (game_over) {
                break;
            }
        }
        return gs;
    }

    public void simulateUnitNextChoicePoint(int player) {
        String game_state_id = "";
        try {
            /* Get game state */
            game_state_id = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            if ( game_state_id.equals("gameover") ) {
                /* Don't simulate. Game is over. */
                LOGGER.fine("Game is over in simulateUntilNextChoicePoint!!!");
            }

            /* Get player to simulate */
            GameState current_game_state = id_to_game_state.get(Integer.parseInt(game_state_id)).clone();
            GameState next_game_state = simulate(current_game_state);

            sendGameState(next_game_state,true);

            SimpleSqrtEvaluationFunction3 eval = new SimpleSqrtEvaluationFunction3();
            double reward = eval.evaluate(player, 1 - player, next_game_state);

            in_pipe.readLine();
            out_pipe.write(reward + "\n");
            out_pipe.flush();

        } catch (Exception e) {
            LOGGER.severe("Game State: " + game_state_id + "," + id_to_game_state.size());
            e.printStackTrace();
        }
    }

    public boolean checkUnitActivity(GameState gs, int sim_player) {
        /* sim_player == -1 when checking for any player */
        if ( sim_player == -1 ) {
            for ( Unit u : gs.getUnits() ) {
                if (u.getPlayer() != -1) {
                    UnitAction uact = gs.getUnitAction(u);
                    if ((uact == null || uact.getType() == UnitAction.TYPE_NONE)) {
                        return true;
                    }
                }
            }
        } else {
            for ( Unit u : gs.getUnits() ) {
                if (u.getPlayer() != -1 && sim_player == u.getPlayer()) {
                    UnitAction uact = gs.getUnitAction(u);
                    if ((uact == null || uact.getType() == UnitAction.TYPE_NONE)) {
                        return true;
                    }
                }
            }
        }
        LOGGER.fine("No player is able to execute an action at this time....");
        return false;
    }

    public void canIssueActions(int player) {
        /* Check if a player can issue an action (default to max)
         Player should always be the current player (i.e max player)
         Should send "true" if max can execute and "false" if min can execute or if none can execute.
        */
        try {
            String gs_string_id = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            GameState gs = id_to_game_state.get(Integer.parseInt(gs_string_id));

            boolean is_max_avail = checkUnitActivity(gs,player);
            LOGGER.fine("Is action for max available?  " + is_max_avail);
            in_pipe.readLine();
            out_pipe.write(is_max_avail + "\n");
            out_pipe.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public PlayerAction getActionsFromCPP(GameState gs) {
        try {
            String action_name;
            PlayerAction next_action = new PlayerAction();
            while ( !(action_name = in_pipe.readLine() ).equals("done") ) {
                LOGGER.fine("Received action from C++ side: " + action_name);
                List< Pair<Unit,UnitAction> > unit_to_unit_action_list = PlayerAction.fromJSON(action_name,gs,utt).getActions();
                for ( Pair<Unit,UnitAction> ua : unit_to_unit_action_list ) {
                    if ( ua.m_a != null && (gs.getUnitAction(ua.m_a) == null) ) {
                        LOGGER.fine("Able to add action!: " + action_name);
                        next_action.addUnitAction(ua.m_a, ua.m_b);
                    }
                }
                out_pipe.write("success\n");
                out_pipe.flush();
            }
            out_pipe.write("success\n");
            out_pipe.flush();
            return next_action;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public PlayerAction communicateWithCPP(GameState gs, int player) {
        try {
            /* Wait for message */
            String message = in_pipe.readLine();
            LOGGER.fine("Command received by C++ side: " + message);

            out_pipe.write("message\n");
            out_pipe.flush();

            switch (message) {
                case "execute":
                    /* Get a state and action, and return the next state */
                    execute();
                    break;
                case "simulateUntilNextChoicePoint":
                    simulateUnitNextChoicePoint(player);
                    break;
                case "canIssueActions":
                    canIssueActions(player);
                    break;
                case "getAction":
                    getNextAction(player);
                    break;
                case "currentState":
                    id_to_game_state.clear();
                    current_id = 0;
                    sendGameState(gs,true);
                    break;
                case "nextAction":
                    PlayerAction act = getActionsFromCPP(gs);
                    return (act == null) ? new PlayerAction() : act;
            }
        } catch (Exception e) {
        }
        return null;
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {

        if ( no_planning_required ) {
            /* Attack with worker rush...because we know that planning in these small environments is not feasible */
            return worker_rush.getAction(player,gs);
        } else {
            long start_time = System.currentTimeMillis();
            LOGGER.info("Finding next action to take.");

            // wait for ack:
            in_pipe.readLine();
            out_pipe.write("no gg\n");
            out_pipe.flush();

            actual_current_game_state = gs;
            PlayerAction act = null;
            while (true) {
                try {
                    act = communicateWithCPP(gs, player);
                    if (act != null) {
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            long end_time = System.currentTimeMillis();
            LOGGER.info("Action acquired: " + act + ", time: " + (end_time - start_time));
            return act;
        }
    }

    @Override
    public void preGameAnalysis(GameState gs, long milliseconds) throws Exception {
        /* Minimum 10x10 map required for any planning */
        if ( gs.getPhysicalGameState().getHeight()*gs.getPhysicalGameState().getWidth() < 100 ) {
            /* Start simple worker rush */
            in_pipe.readLine();
            out_pipe.write("gameover\n");
            out_pipe.flush();
            no_planning_required = true;
        } else {
            /* FUTURE WORK: Look into doing a more in-depth map analysis */
            /* Determine the best location to put the barracks */

            /* From the base, get the closest resource. Put base in opposite direction */
            unit_table.clear();
            for (Unit u : gs.getUnits()) {
                ArrayList<Unit> tmp;
                if (unit_table.containsKey(u.getType().name)) {
                    tmp = unit_table.get(u.getType().name);
                    tmp.add(u);
                    unit_table.replace(u.getType().name, tmp);
                } else {
                    tmp = new ArrayList<Unit>();
                    tmp.add(u);
                    unit_table.put(u.getType().name, tmp);
                }
            }
            player_to_barrack_locations = new LinkedHashMap<>();

            int width = gs.getPhysicalGameState().getWidth();
            MAP_WIDTH = width;
            int height = gs.getPhysicalGameState().getHeight();
            MAP_HEIGHT = height;

            for ( Unit base_unit : unit_table.get("Base") ) {
                ArrayList<Unit> closest_resource_units = new ArrayList<>();
                for ( Unit resource_unit : unit_table.get("Resource") ) {
                    double dist = Math.abs(resource_unit.getX() - base_unit.getX()) + Math.abs(resource_unit.getY() - base_unit.getY());
                    if (dist < 4.0) {
                        closest_resource_units.add(resource_unit);
                    }
                }
                int base_x = base_unit.getX();
                int base_y = base_unit.getY();

                ArrayList< Pair_CCG<Integer, Integer> > best_barrack_locations = new ArrayList<>();
                if ( closest_resource_units.isEmpty() ) {
                    /* Can place it anywhere near the base */
                    if ( base_x+1 < width && base_y+1 < height ) {
                        best_barrack_locations.add(new Pair_CCG<Integer, Integer>(base_x + 1, base_y + 1));
                    }
                    if ( base_x-1 >= 0 && base_y-1 >= 0 ) {
                        best_barrack_locations.add(new Pair_CCG<Integer, Integer>(base_x - 1, base_y - 1));
                    }
                    if ( base_x+1 < width && base_y-1 >= 0 ) {
                        best_barrack_locations.add(new Pair_CCG<Integer, Integer>(base_x + 1, base_y - 1));
                    }
                    if ( base_x-1 >= 0 && base_y+1 < height ) {
                        best_barrack_locations.add(new Pair_CCG<Integer, Integer>(base_x - 1, base_y + 1));
                    }
                } else {
                    /* Place farthest away from the resources to not restrict workers to resources */
                    ArrayList<Integer> directions_to_place_barracks = new ArrayList<Integer>();
                    for ( Unit resource : closest_resource_units) {
                        if (base_x - resource.getX() < 0 && base_x - 1 >= 0) {
                            directions_to_place_barracks.add(UnitAction.DIRECTION_LEFT);
                        }
                        if (base_x - resource.getX() > 0 && base_x + 1 < width) {
                            directions_to_place_barracks.add(UnitAction.DIRECTION_RIGHT);
                        }
                        if (base_y - resource.getY() < 0 && base_y - 1 >= 0) {
                            directions_to_place_barracks.add(UnitAction.DIRECTION_UP);
                        }
                        if (base_y - resource.getY() > 0 && base_y + 1 < height) {
                            directions_to_place_barracks.add(UnitAction.DIRECTION_DOWN);
                        }
                    }

                    int up_or_down = base_y+1;
                    int freq_up = Collections.frequency(directions_to_place_barracks,UnitAction.DIRECTION_UP);
                    int freq_down = Collections.frequency(directions_to_place_barracks,UnitAction.DIRECTION_DOWN);
                    if ( freq_up > freq_down ) {
                        up_or_down = base_y-1;
                    }

                    int left_or_right = base_x+1;
                    int freq_left = Collections.frequency(directions_to_place_barracks,UnitAction.DIRECTION_LEFT);
                    int freq_right = Collections.frequency(directions_to_place_barracks,UnitAction.DIRECTION_RIGHT);
                    if ( freq_left > freq_right ) {
                        left_or_right = base_x-1;
                    }
                    best_barrack_locations.add(new Pair_CCG<Integer, Integer>(left_or_right, up_or_down));
                }

                if ( player_to_barrack_locations.containsKey(base_unit.getPlayer()) ) {
                    ArrayList< Pair_CCG< Integer,Integer> > tmp = player_to_barrack_locations.get(base_unit.getPlayer());
                    tmp.addAll(best_barrack_locations);
                    player_to_barrack_locations.replace(base_unit.getPlayer(),tmp);
                } else {
                    player_to_barrack_locations.put(base_unit.getPlayer(), best_barrack_locations);
                }
            }
            no_planning_required = false;
        }
    }

    @Override
    public MicroCCG_v2 clone() {
        return new MicroCCG_v2(TIME_BUDGET, ITERATIONS_BUDGET, server_address, server_port, utt);
    }

    @Override
    public String statisticsString() {
        return "";
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        List<ParameterSpecification> l = new ArrayList<>();

        l.add(new ParameterSpecification("Server Address", String.class, server_address));
        l.add(new ParameterSpecification("Server Port", Integer.class, server_port));
        l.add(new ParameterSpecification("Language", Integer.class, LANGUAGE_JSON));

        return l;
    }

    @Override
    public void gameOver(int winner) throws Exception {
        /* Gracefully shut down the CCG adversarial planner */
        try {
            in_pipe.readLine();
            out_pipe.write("gameover\n");
            out_pipe.flush();
        } catch ( Exception e ) {
        }
        p.waitFor(2, TimeUnit.SECONDS);
        p.destroy();
        System.out.println("GG!");
    }
}