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

    private Logger LOGGER = Logger.getLogger(MicroCCG_v2.class.getName());
    /* NOTE: All loggers must be commented out prior to tournament */

    private static int DEBUG = 1;
    private static final int LANGUAGE_XML = 1;
    private UnitTypeTable utt = null;

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
    private ArrayList<Unit> all_available_enemy_units;
    private Random rand;

    /* For parameter policy */
    private Unit player_construction_unit = null;
    private int player_time_to_finish = 0;
    private boolean player_constructing = false;

    private Unit opponent_construction_unit = null;
    private int opponent_time_to_finish = 0;
    private boolean opponent_constructing = false;

    public GameState actual_current_game_state;

    private LinkedHashMap<Integer,GameState> id_to_game_state;
    private int current_id;

    private String [] unit_attack_priority = { "Barracks", "Light", "Heavy", "Ranged", "Base", "Worker"};

    private boolean no_planning_required = false;
    private WorkerRush worker_rush;

    public MicroCCG_v2(UnitTypeTable a_utt) {
        super(new AStarPathFinding(), 100, -1);
        worker_rush = new WorkerRush(a_utt);

        LOGGER.setLevel(Level.SEVERE);
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>();
        all_usable_player_units = new ArrayList<Unit>();
        player_worker_units = new ArrayList<Unit>();
        all_available_resources = new ArrayList<Unit>();
        all_available_enemy_units = new ArrayList<Unit>();
        all_player_base_units = new ArrayList<Unit>();
        rand = new Random(System.currentTimeMillis());
        id_to_game_state = new LinkedHashMap<Integer,GameState>();
        current_id = 0;
    }

    public MicroCCG_v2(int mt, int mi, UnitTypeTable a_utt) {
        super(new AStarPathFinding(), mt, mi);
        worker_rush = new WorkerRush(a_utt);

        LOGGER.setLevel(Level.SEVERE);
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>();
        all_usable_player_units = new ArrayList<Unit>();
        player_worker_units = new ArrayList<Unit>();
        all_available_resources = new ArrayList<Unit>();
        all_available_enemy_units = new ArrayList<Unit>();
        all_player_base_units = new ArrayList<Unit>();
        rand = new Random(System.currentTimeMillis());

        id_to_game_state = new LinkedHashMap<Integer,GameState>();
        current_id = 0;
    }


    public MicroCCG_v2(int mt, int mi, String a_sa, int a_port, UnitTypeTable a_utt) {
        super(new AStarPathFinding(), mt, mi);
        worker_rush = new WorkerRush(a_utt);

        LOGGER.setLevel(Level.SEVERE);
        server_address = a_sa;
        server_port = a_port;
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>();
        all_usable_player_units = new ArrayList<Unit>();
        player_worker_units = new ArrayList<Unit>();
        //all_available_resources = new ArrayList<Unit>();
        all_available_enemy_units = new ArrayList<Unit>();
        all_player_base_units = new ArrayList<Unit>();
        rand = new Random(System.currentTimeMillis());

        id_to_game_state = new LinkedHashMap<Integer,GameState>();
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
            out_pipe.append("budget " + TIME_BUDGET + "," + ITERATIONS_BUDGET + "\n");
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
        /*  Return a null action or an empty action?
        *   Returning null seems to cause the planner to never plan correctly...so..returning an empty action might be better.
        *   Is this scientifically valid? -> Yes because even if we fail, another action might be executed by a different agent. ..
        *   If the same agent executes an action and fails, the next action may not fail (there is more than one agent in the game..
        *   and a conflict might have occurred).
        * */

        /* We might conflict for resources when creating new units */
        unit_table.clear();
        for (Unit u : gs.getUnits()) {
            ArrayList<Unit> tmp = null;
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
            /* All offensive units attack */
            /* Send all units to attack the most important bases. */
//            Unit highest_priority_enemy_unit = null;
//            for (String u_type : all_unit_types ) {
//                if ( unit_table.containsKey(u_type) ) {
//                    for (Unit u : unit_table.get(u_type)) {
//                        if (u.getPlayer() != -1 && u.getPlayer() == (1 - player)) {
//                            /* Enemy unit */
//                            highest_priority_enemy_unit = u;
//                            break;
//                        }
//                    }
//                }
//
//                if ( highest_priority_enemy_unit != null ) {
//                    break;
//                }
//            }
//
//            if ( highest_priority_enemy_unit == null ) {
//                /* All enemy units are dead...we win! */
//                return ret_action;
//            }
//
//            /* Attack the highest priority unit */
//            GameState gs_tmp = gs.clone();
//            UnitAction test_action = new UnitAction(UnitAction.TYPE_ATTACK_LOCATION,
//                    highest_priority_enemy_unit.getX(), highest_priority_enemy_unit.getY());
//            for ( Unit attacking_unit : attack_units ) {
//                if (attacking_unit.canExecuteAction(test_action, gs)) {
//                    ret_action.addUnitAction(attacking_unit, test_action);
//                } else {
//                    /* Move towards the unit */
//                    UnitAction move = pf.findPathToAdjacentPosition(attacking_unit,
//                            highest_priority_enemy_unit.getX() + highest_priority_enemy_unit.getY() * gs.getPhysicalGameState().getWidth(), gs, null);
//                    if (move != null) {
//                        ret_action.addUnitAction(attacking_unit, move);
//                        gs_tmp.issueSafe(ret_action);
//                    }
//                }
//            }
            for (Unit attacking_unit : attack_units) {
                Unit closest_enemy_unit = null;
                double min_dist = Math.pow(2, 10);
                for (String u_type : unit_attack_priority) {
                    if (unit_table.containsKey(u_type)) {
                        for (Unit u : unit_table.get(u_type)) {
                            if (u.getPlayer() != -1 && u.getPlayer() == (1 - player)) {
                                /* Manhattan distance */
                                double dist = Math.abs(attacking_unit.getX() - u.getX()) + Math.abs(attacking_unit.getY() - u.getY());
                                if (dist < min_dist && pf.pathToPositionInRangeExists(attacking_unit,
                                        u.getX() + u.getY() * gs.getPhysicalGameState().getWidth(), attacking_unit.getAttackRange(), gs, null)) {
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

                /*  Movement behavior of offensive units
                *   Optimally, this would be attack, then move.
                * */

                /* Check if we are in exactly attacking range of the enemy */
                int attacking_x = attacking_unit.getX();
                int attacking_y = attacking_unit.getY();
                int enemy_x = closest_enemy_unit.getX();
                int enemy_y = closest_enemy_unit.getY();
                int range = attacking_unit.getAttackRange();

                boolean run_away = false;
                if ( attacking_unit.getType().name.equals("Ranged") ) {
                    LinkedList<Unit> units_too_close = (LinkedList<Unit>)gs.getPhysicalGameState().getUnitsAround(attacking_x,attacking_y,range-1);
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
                                closest_enemy_unit.getX() + closest_enemy_unit.getY() * gs.getPhysicalGameState().getWidth(), gs, null);
                        if (move != null) {
                            ret_action.addUnitAction(attacking_unit, move);
                        }
                    }
                } else {
                    /* Run away from the opponent unit */
                    UnitAction move = null;
                    if ( attacking_x - enemy_x < 0 && attacking_x - 1 >= 0 ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_LEFT);
                    }
                    if ( attacking_x - enemy_x > 0 && attacking_x + 1 < gs.getPhysicalGameState().getWidth() ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_RIGHT);
                    }
                    if ( attacking_y - enemy_y < 0 && attacking_y - 1 >= 0 ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_UP);
                    }
                    if ( attacking_y - enemy_y > 0 && attacking_y + 1 < gs.getPhysicalGameState().getHeight() ) {
                        move = new UnitAction(UnitAction.TYPE_MOVE,UnitAction.DIRECTION_DOWN);
                    }
                    if ( move != null ) {
                        ret_action.addUnitAction(attacking_unit, move);
                    }
                }
            }
        }

        int rand_num = 0;

        switch (action_name) {
            case "attack":
                /* Should never be here */
                return ret_action;
            case "return":
                /* Get a unit with resources that's closest to a base */
                boolean [][] free_map = gs.getAllFree();

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

                GameState gs_tmp = gs.clone();
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
                                    closest_base.getX() + closest_base.getY() * gs.getPhysicalGameState().getWidth(), gs_tmp, null);
                            if ( move != null ) {
                                ret_action.addUnitAction(wrkr, move);
                                gs_tmp.issueSafe(ret_action);
                            } else {
                                /* Move randomly? */
                                ArrayList<Integer> random_move = new ArrayList<Integer>();
                                if ( wrkr.getX()+1 < free_map.length && free_map[wrkr.getX()+1][wrkr.getY()] ) {
                                    random_move.add(UnitAction.DIRECTION_RIGHT);
                                }
                                if ( wrkr.getY()+1 < free_map[0].length && free_map[wrkr.getX()][wrkr.getY()+1] ) {
                                    random_move.add(UnitAction.DIRECTION_DOWN);
                                }
                                if ( wrkr.getX()-1 >= 0 && free_map[wrkr.getX()-1][wrkr.getY()] ) {
                                    random_move.add(UnitAction.DIRECTION_LEFT);
                                }
                                if ( wrkr.getY()-1 >= 0 && free_map[wrkr.getX()][wrkr.getY()-1] )  {
                                    random_move.add(UnitAction.DIRECTION_UP);
                                }
                                if ( !random_move.isEmpty() ) {
                                    ret_action.addUnitAction(wrkr, new UnitAction(UnitAction.TYPE_MOVE,
                                            random_move.get(rand.nextInt(random_move.size()))));
                                    gs_tmp.issueSafe(ret_action);
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
                gs_tmp = gs.clone();
                for ( int i = 0; i < player_worker_units.size(); i++) {
                    Unit worker_unit = player_worker_units.get(i);
                    if ( !(worker_unit.getResources() == 0) ) {
                            continue;
                    }

                    while ( !all_available_resources.isEmpty() ) {
                        Unit closest_resource = null;
                        double min_dist = Math.pow(2, 10);
                        for (Unit u : all_available_resources) {
                            /* Manhattan distance */
                            double dist = Math.abs(worker_unit.getX() - u.getX()) + Math.abs(worker_unit.getY() - u.getY());
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
                        int x = worker_unit.getX();
                        int y = worker_unit.getY();
                        if ((x + 1 == closest_resource.getX()) && (y == closest_resource.getY())) {
                            direction = UnitAction.DIRECTION_RIGHT;
                        }
                        if ((x == closest_resource.getX()) && (y + 1 == closest_resource.getY())) {
                            direction = UnitAction.DIRECTION_DOWN;
                        }
                        if ((x - 1 == closest_resource.getX()) && (y == closest_resource.getY())) {
                            direction = UnitAction.DIRECTION_LEFT;
                        }
                        if ((x == closest_resource.getX()) && (y - 1 == closest_resource.getY())) {
                            direction = UnitAction.DIRECTION_UP;
                        }

                        if (direction > -1) {
                            ret_action.addUnitAction(worker_unit, new UnitAction(UnitAction.TYPE_HARVEST, direction));
                            break;
                        } else {
                            /* Move towards the resource */
                            UnitAction move = pf.findPathToAdjacentPosition(worker_unit,
                                    closest_resource.getX() + closest_resource.getY() * gs.getPhysicalGameState().getWidth(), gs_tmp, null);
                            if (move != null) {
                                ret_action.addUnitAction(worker_unit, move);
                                gs_tmp.issueSafe(ret_action);
                                break;
                            } else {
                                /* If we can't move to it, then no other worker can */
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
                free_map = gs.getAllFree();

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

                /* Number of allowed workers should be based on the size of the map */
                /* Assume 12x12 minimum map */
                int num_allowable_workers = gs.getPhysicalGameState().getWidth()/4;//(gs.getPhysicalGameState().getWidth()*gs.getPhysicalGameState().getHeight())/36;
                if ( all_player_workers.size() > num_allowable_workers) {
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
                if (x + 1 < gs.getPhysicalGameState().getWidth() && free_map[x + 1][y] ) {
                    free_positions.add(UnitAction.DIRECTION_RIGHT);
                }
                if (y + 1 < gs.getPhysicalGameState().getHeight() && free_map[x][y + 1]) {
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
                free_map = gs.getAllFree();
                if (x + 1 < gs.getPhysicalGameState().getWidth() && free_map[x + 1][y] ) {
                    free_positions.add(UnitAction.DIRECTION_RIGHT);
                }
                if (y + 1 < gs.getPhysicalGameState().getHeight() && free_map[x][y + 1]) {
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

                /* Get a random direction */
                rand_num = rand.nextInt(free_positions.size());
                ret_action.addUnitAction(construction_unit, new UnitAction(UnitAction.TYPE_PRODUCE, free_positions.get(rand_num), utt.getUnitType(unit_produced)));
                if (min_max.equals("min") ) {
                    opponent_time_to_finish = gs.getTime();
                    opponent_constructing = true;
                    opponent_time_to_finish += ret_action.getAction(construction_unit).ETA(construction_unit);
                } else {
                    player_time_to_finish = gs.getTime();
                    player_constructing = true;
                    player_time_to_finish += ret_action.getAction(construction_unit).ETA(construction_unit);
                }
                return ret_action;
            case "producelight":
            case "produceheavy":
            case "produceranged":
                free_map = gs.getAllFree();

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
                if (x + 1 < gs.getPhysicalGameState().getWidth() && free_map[x + 1][y]) {
                    free_positions.add(UnitAction.DIRECTION_RIGHT);
                }
                if (y + 1 < gs.getPhysicalGameState().getHeight() && free_map[x][y + 1]) {
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

    public void execute(int pid) {
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
                    execute(player);
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

            //sendActions(gs,player);
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
        /* Minimum 12x12 map required for any planning */
        if ( gs.getPhysicalGameState().getHeight()*gs.getPhysicalGameState().getWidth() < 144 ) {
            /* Start simple worker rush */
            String message = in_pipe.readLine();
            out_pipe.write("gameover\n");
            out_pipe.flush();
            no_planning_required = true;
        } else {
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
        l.add(new ParameterSpecification("Language", Integer.class, LANGUAGE_XML));

        return l;
    }

    @Override
    public void gameOver(int winner) throws Exception {
        /* Send over winner */
        try {
            String message = in_pipe.readLine();
            out_pipe.write("gameover\n");
            out_pipe.flush();
//            p.waitFor(2, TimeUnit.SECONDS);
//            p.destroy();
//            System.out.println("GG!");
        } catch ( Exception e ) {
        }
        p.waitFor(2, TimeUnit.SECONDS);
        p.destroy();
        System.out.println("GG!");
    }
}

//    public PlayerAction getNextActionNonAbstract(GameState gs) {
//        try {
//            String action;
//            PlayerAction next_action = new PlayerAction();
//            while ( !(action = in_pipe.readLine() ).equals("done") ) {
//                /* Parse action */
//                List< Pair<Unit,UnitAction> > unit_to_unit_action_list = PlayerAction.fromJSON(action,gs,utt).getActions();
//                for ( Pair<Unit,UnitAction> ua : unit_to_unit_action_list ) {
//                    if ( ua.m_a != null && (gs.getUnitAction(ua.m_a) == null) ) {
//                        next_action.addUnitAction(ua.m_a, ua.m_b);
//                    }
//                }
//                out_pipe.write("success\n");
//                out_pipe.flush();
//            }
//            out_pipe.write("success\n");
//            out_pipe.flush();
//            return next_action;
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return null;
//    }