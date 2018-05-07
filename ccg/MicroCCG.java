package ai.ccg;

import ai.abstraction.AbstractionLayerAI;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.ParameterSpecification;
import ai.evaluation.SimpleSqrtEvaluationFunction3;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import rts.*;
import rts.units.Unit;
import rts.units.UnitTypeTable;
import util.Pair;
import util.XMLWriter;

import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * Created by pavankantharaju on 11/16/17.
 */

/* TODO: Extend this so that we can also plan using CCGs */

public class MicroCCG extends AbstractionLayerAI {

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
    public final String rec_file = "src/ai/ccg/domain.microRTS.cig2018.planrec.nomove.elx";
    public final String plan_file = "src/ai/ccg/domain.microRTS.cig2018.planning.nomove.elx";

    private Process p;

    public String cur_strategy = "";
    public String cur_enemy_strategy = "";

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

    int [] directions = { UnitAction.DIRECTION_RIGHT, UnitAction.DIRECTION_DOWN, UnitAction.DIRECTION_LEFT, UnitAction.DIRECTION_UP };

    public PhysicalGameState prev_physicalgamestate;

    private LinkedHashMap<Integer,GameState> id_to_game_state;
    private LinkedHashMap<Integer,PlayerAction> id_to_player_action;
    private int current_id;
    private int current_action_id;

    public MicroCCG(UnitTypeTable a_utt) {
        //super(new GreedyPathFinding(), 100, -1);
        super(new AStarPathFinding(), 100, -1);
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>();
        all_usable_player_units = new ArrayList<Unit>();
        player_worker_units = new ArrayList<Unit>();
        all_available_resources = new ArrayList<Unit>();
        all_available_enemy_units = new ArrayList<Unit>();
        all_player_base_units = new ArrayList<Unit>();
        rand = new Random(System.currentTimeMillis());

        id_to_game_state = new LinkedHashMap<Integer,GameState>();
        id_to_player_action = new LinkedHashMap<Integer,PlayerAction>();
        current_id = 0;
        current_action_id = 0;
    }

    public MicroCCG(int mt, int mi, UnitTypeTable a_utt) {
        //super(new GreedyPathFinding(), mt, mi);
        super(new AStarPathFinding(), mt, mi);
        utt = a_utt;
        unit_table = new HashMap<String, ArrayList<Unit>>();
        all_usable_player_units = new ArrayList<Unit>();
        player_worker_units = new ArrayList<Unit>();
        all_available_resources = new ArrayList<Unit>();
        all_available_enemy_units = new ArrayList<Unit>();
        all_player_base_units = new ArrayList<Unit>();
        rand = new Random(System.currentTimeMillis());

        id_to_game_state = new LinkedHashMap<Integer,GameState>();
        id_to_player_action = new LinkedHashMap<Integer,PlayerAction>();
        current_id = 0;
        current_action_id = 0;
    }


    public MicroCCG(int mt, int mi, String a_sa, int a_port, UnitTypeTable a_utt) {
        //super(new GreedyPathFinding(), mt, mi);
        super(new AStarPathFinding(), mt, mi);
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
        id_to_player_action = new LinkedHashMap<Integer,PlayerAction>();
        current_id = 0;
        current_action_id = 0;
    }

    public void setupEnvironment() {
        try {
            ProcessBuilder build = new ProcessBuilder(planner_executable,Integer.toString(server_port),rec_file,plan_file);
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
        in_pipe = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out_pipe = new PrintWriter(socket.getOutputStream(), true);
    }

    @Override
    public void reset() {

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
        /* Instead of sending the game state, send an ID coorresponding to its location in the hashmap */
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
//                StringWriter buffer = new StringWriter();
//                XMLWriter w = new XMLWriter(buffer, " ");
//                gs.toxml(w);
//                out_pipe.write(buffer.toString());
//                out_pipe.flush();
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
                /* Can't issue action.... */
                return ret_action;
            }

            /* Better idea...if you're producing...don't produce any other units */
            boolean is_producing_units = false;
            if ( unit_table.containsKey("Base") ) {
                for ( Unit u : unit_table.get("Base") ) {
                    if ( u.getPlayer() == player ) {
                        if ( gs.getUnitActions().containsKey(u) ) {
                            if (gs.getUnitActions().get(u).action.getType() == UnitAction.TYPE_PRODUCE &&
                                    gs.getUnitActions().get(u).action.getUnitType().name.equals("Worker")) {
                                is_producing_units = true;
                                break;
                            }
                        }
                    }
                }
            }

            if ( is_producing_units ) {
                return ret_action;
            }

            if ( unit_table.containsKey("Worker") ) {
                for ( Unit u : unit_table.get("Worker") ) {
                    if ( u.getPlayer() == player ) {
                        if (gs.getUnitActions().containsKey(u)) {
                            if ( gs.getUnitActions().get(u).action.getType() == UnitAction.TYPE_PRODUCE &&
                                    gs.getUnitActions().get(u).action.getUnitType().name.equals("Barracks") ) {
                                is_producing_units = true;
                                break;
                            }
                            if ( gs.getUnitActions().get(u).action.getType() == UnitAction.TYPE_PRODUCE &&
                                    gs.getUnitActions().get(u).action.getUnitType().name.equals("Base") ) {
                                is_producing_units = true;
                                break;
                            }

                        }
                    }
                }
            }

            if ( is_producing_units ) {
                return ret_action;
            }

            if ( unit_table.containsKey("Barracks") ) {
                for ( Unit u : unit_table.get("Barracks") ) {
                    if ( u.getPlayer() == player ) {
                        if (gs.getUnitActions().containsKey(u)) {
                            if (gs.getUnitActions().get(u).action.getType() == UnitAction.TYPE_PRODUCE &&
                                    ( gs.getUnitActions().get(u).action.getUnitType().name.equals("Light")
                                            ||  gs.getUnitActions().get(u).action.getUnitType().name.equals("Heavy")
                                            ||  gs.getUnitActions().get(u).action.getUnitType().name.equals("Ranged") ) ) {
                                is_producing_units = true;
                                break;
                            }
                        }
                    }
                }
            }

            if ( is_producing_units ) {
                return ret_action;
            }
        }

//        if ( min_max.equals("min") ) {
//            if ( gs.getTime() > opponent_time_to_finish && opponent_constructing ) {
//                opponent_constructing = false;
//                opponent_construction_unit = null;
//            }
//        } else {
//            if ( gs.getTime() > player_time_to_finish && player_constructing ) {
//                player_constructing = false;
//                player_construction_unit = null;
//            }
//        }

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

        int rand_num = 0;

        switch (action_name) {
            case "attack":
                /* Attack needs to be fair to each unit... */
                /* If a unit can't attack, move towards the unit? Is this scientifically valid? */
                /* Find closest unit that is reachable from the current attacking unit
                 * */
                ArrayList<Unit> attack_units = new ArrayList<Unit>();
                for (Unit u : all_usable_player_units) {
                    if ( !u.getType().name.equals("Worker") && !u.getType().name.equals("Barracks") && !u.getType().name.equals("Base") ) {
                        attack_units.add(u);
                    }
                }

                if ( attack_units.isEmpty() ) {
                    return ret_action;
                }

                if ( attack_units.size() > 4 ) {
                    /* Group-based attacking */
                    ArrayList<Unit> actual_attacking_units = new ArrayList<Unit>();
                    int i = 0;
                    int size = attack_units.size();
                    while (i < 4) {
                        rand_num = rand.nextInt(attack_units.size());
                        actual_attacking_units.add(attack_units.get(rand_num));
                        attack_units.remove(attack_units.get(rand_num));
                        i++;
                    }
                    /* Issue all 4 at the same time */
                    for (Unit attacking_unit : actual_attacking_units) {
                        Unit closest_enemy_unit = null;
                        double min_dist = Math.pow(2, 10);
                        all_available_enemy_units.clear();
                        for (String u_type : unit_table.keySet()) {
                            for (Unit u : unit_table.get(u_type)) {
                                if (u.getPlayer() != -1 && u.getPlayer() == (1 - player)) {
                                    double dist = Math.sqrt(Math.pow(attacking_unit.getX() - u.getX(), 2.0) + Math.pow(attacking_unit.getY() - u.getY(), 2.0));
                                    if (dist < min_dist && pf.pathToPositionInRangeExists(attacking_unit,
                                            u.getX() + u.getY() * gs.getPhysicalGameState().getWidth(), 1, gs, null)) {
                                        closest_enemy_unit = u;
                                        min_dist = dist;
                                    }
                                }
                            }
                        }

                        if (closest_enemy_unit == null) {
                            /* This technically means that all enemy units are dead...and we won */
                            return ret_action;
                        }

                        UnitAction test_action = new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, closest_enemy_unit.getX(), closest_enemy_unit.getY());
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
                    }
                    return ret_action;
                }

                /* Single-unit attacking */
                rand_num = rand.nextInt(attack_units.size());
                Unit attacking_unit = attack_units.get(rand_num);
                Unit closest_enemy_unit = null;
                double min_dist = Math.pow(2, 10);
                all_available_enemy_units.clear();
                for (String u_type : unit_table.keySet()) {
                    for (Unit u : unit_table.get(u_type)) {
                        if (u.getPlayer() != -1 && u.getPlayer() == (1 - player)) {
                            double dist = Math.sqrt(Math.pow(attacking_unit.getX() - u.getX(), 2.0) + Math.pow(attacking_unit.getY() - u.getY(), 2.0));
                            if (dist < min_dist && pf.pathToPositionInRangeExists(attacking_unit,
                                    u.getX() + u.getY() * gs.getPhysicalGameState().getWidth(), 1, gs, null)) {
                                closest_enemy_unit = u;
                                min_dist = dist;
                            }
                        }
                    }
                }

                if (closest_enemy_unit == null) {
                    /* This technically means that all enemy units are dead...and we won */
                    return ret_action;
                }

                UnitAction test_action = new UnitAction(UnitAction.TYPE_ATTACK_LOCATION, closest_enemy_unit.getX(), closest_enemy_unit.getY());
                if (attacking_unit.canExecuteAction(test_action, gs)) {
                    ret_action.addUnitAction(attacking_unit, test_action);
                } else {
                    /* Move towards the unit */
                    UnitAction move = pf.findPathToAdjacentPosition(attacking_unit,
                            closest_enemy_unit.getX() + closest_enemy_unit.getY() * gs.getPhysicalGameState().getWidth(), gs, null);
                    if (move != null) {
                        ret_action.addUnitAction(attacking_unit, move);
                    } else {
                        return ret_action;
                    }
                }
                return ret_action;
            case "return":
                /* Return with resources */
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

                Unit closest_worker = null;
                Unit closest_base = null;

                int direction = -1;
                for (Unit wrkr : player_worker_units) {
                    int x = wrkr.getX();
                    int y = wrkr.getY();
                    if (wrkr.getResources() > 0) {
                        closest_worker = wrkr;
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
                        if (direction > -1) {
                            closest_worker = wrkr;
                            break;
                        }
                    }
                }
                if ( closest_worker == null ) {
                    return ret_action;
                }

                if ( direction > -1 ) {
                    ret_action.addUnitAction(closest_worker,  new UnitAction(UnitAction.TYPE_RETURN, direction) );
                } else {
                    /* Move towards the unit */
                    UnitAction move = pf.findPathToAdjacentPosition(closest_worker,
                            closest_base.getX() + closest_base.getY() * gs.getPhysicalGameState().getWidth(), gs, null);
                    if ( move != null ) {
                        ret_action.addUnitAction(closest_worker, move);
                    } else {
                        /* Move randomly? */
                        ArrayList<Integer> random_move = new ArrayList<Integer>();
                        if ( free_map[closest_worker.getX()+1][closest_worker.getY()] ) {
                            random_move.add(UnitAction.DIRECTION_RIGHT);
                        }
                        if ( free_map[closest_worker.getX()][closest_worker.getY()+1] ) {
                            random_move.add(UnitAction.DIRECTION_DOWN);
                        }
                        if ( free_map[closest_worker.getX()-1][closest_worker.getY()] ) {
                            random_move.add(UnitAction.DIRECTION_LEFT);
                        }
                        if (free_map[closest_worker.getX()][closest_worker.getY()-1] )  {
                            random_move.add(UnitAction.DIRECTION_UP);
                        }
                        ret_action.addUnitAction(closest_worker, new UnitAction(UnitAction.TYPE_MOVE,
                                random_move.get(rand.nextInt(random_move.size()))));
                    }
                }
                //ret_action.addUnitAction(closest_worker, new UnitAction(UnitAction.TYPE_RETURN, direction));
                return ret_action;
            case "harvest":
                free_map = gs.getAllFree();

                /* Harvest closest resource */
                all_player_base_units.clear();
                if (unit_table.containsKey("Base")) {
                    for (Unit u : unit_table.get("Base")) {
                        /* Unit is ours and not executing anything */
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

                //System.out.println("You should be here");

                all_available_resources.clear();
                if (unit_table.containsKey("Resource")) {
                    for (Unit u : unit_table.get("Resource")) {
                        all_available_resources.add(u);
                    }
                }

                /* Find the closest resources to a random base...and get the closest worker */
                rand_num = rand.nextInt(all_player_base_units.size());
                Unit random_base = all_player_base_units.get(rand_num);

                Unit closest_resource = null;
                min_dist = Math.pow(2,10);
                for( Unit u : all_available_resources ) {
                    double dist = Math.sqrt( Math.pow(random_base.getX() - u.getX(), 2.0 ) + Math.pow(random_base.getY() - u.getY(), 2.0 ) );
                    if ( dist < min_dist ) {
                        closest_resource = u;
                        min_dist = dist;
                    }
                }
                //System.out.println("Closest resource: " + closest_resource);

                closest_worker = null;
                min_dist = Math.pow(2,10);
                for( Unit u : player_worker_units ) {
                    double dist = Math.sqrt( Math.pow(closest_resource.getX() - u.getX(), 2.0 ) + Math.pow(closest_resource.getY() - u.getY(), 2.0 ) );
                    if ( dist < min_dist && u.getResources() == 0 ) {
                        closest_worker = u;
                        min_dist = dist;
                    }
                }

                //System.out.println("Closest worker: " + closest_worker);

                if (closest_worker == null) {
                    return ret_action;
                }

                /* Check if resource is next to the unit */
                direction = -1;
                int x = closest_worker.getX();
                int y = closest_worker.getY();
                if ( (x + 1 == closest_resource.getX()) && (y == closest_resource.getY()) ) {
                    direction = UnitAction.DIRECTION_RIGHT;
                }
                if ( (x == closest_resource.getX()) && (y + 1 == closest_resource.getY()) ) {
                    direction = UnitAction.DIRECTION_DOWN;
                }
                if ( (x - 1 == closest_resource.getX()) && (y == closest_resource.getY()) ) {
                    direction = UnitAction.DIRECTION_LEFT;
                }
                if ( (x == closest_resource.getX()) && (y - 1 == closest_resource.getY()) ) {
                    direction = UnitAction.DIRECTION_UP;
                }

                if ( direction > -1 ) {
                    ret_action.addUnitAction(closest_worker, new UnitAction(UnitAction.TYPE_HARVEST, direction));
                } else {
                    /* Move towards the unit */
                    UnitAction move = pf.findPathToAdjacentPosition(closest_worker,
                            closest_resource.getX() + closest_resource.getY() * gs.getPhysicalGameState().getWidth(), gs, null);
                    if ( move != null ) {
                        ret_action.addUnitAction(closest_worker, move);
                    } else {
                        /* Move randomly? */
                        ArrayList<Integer> random_move = new ArrayList<Integer>();
                        if ( free_map[closest_worker.getX()+1][closest_worker.getY()] ) {
                            random_move.add(UnitAction.DIRECTION_RIGHT);
                        }
                        if ( free_map[closest_worker.getX()][closest_worker.getY()+1] ) {
                            random_move.add(UnitAction.DIRECTION_DOWN);
                        }
                        if ( free_map[closest_worker.getX()-1][closest_worker.getY()] ) {
                            random_move.add(UnitAction.DIRECTION_LEFT);
                        }
                        if (free_map[closest_worker.getX()][closest_worker.getY()-1] )  {
                            random_move.add(UnitAction.DIRECTION_UP);
                        }
                        ret_action.addUnitAction(closest_worker, new UnitAction(UnitAction.TYPE_MOVE,
                                random_move.get(rand.nextInt(random_move.size()))));
                    }
                }

                //ret_action.addUnitAction(closest_worker, new UnitAction(UnitAction.TYPE_HARVEST, direction))

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
                if ( all_player_workers.size() > 2 ) { /* This seems optimal */
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

                x = free_bases.get(rand_num).getX();
                y = free_bases.get(rand_num).getY();

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

                /* First, make sure there's a 2x2 range free */
                Collection<Unit> all_units_in_range = gs.getPhysicalGameState().getUnitsAround(x,y,1);
                all_units_in_range.remove(construction_unit);
                if ( !all_units_in_range.isEmpty() ) {
                    /* Move */
                    for (int i = x - 5; i < x + 5; i++) {
                        for (int j = y - 5; j < y + 5; j++) {
                            /* Move to a 2x2 position around x,y */
                            all_units_in_range = gs.getPhysicalGameState().getUnitsAround(i,j,1);
                            if ( all_units_in_range.isEmpty() ) {
                                UnitAction move = pf.findPath(construction_unit, i + j * gs.getPhysicalGameState().getWidth(), gs, null);
                                if (move != null) {
                                    ret_action.addUnitAction(construction_unit, move);
                                    return ret_action;
                                }
                            }
                        }
                    }
                    return ret_action;
                }

                /* Get a random direction */
                rand_num = rand.nextInt(directions.length);
                ret_action.addUnitAction(construction_unit, new UnitAction(UnitAction.TYPE_PRODUCE, directions[rand_num], utt.getUnitType(unit_produced)));
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
        return null;
    }

    public void execute(int pid) {
        try {
            String game_state_id = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            //System.out.println("Game state: " + game_state);

            String action_name = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            //System.out.println("Action to execute: " + action_name);

            String min_max_player = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            int player = min_max_player.equals("max") ? pid : (1 - pid);

            /* Return the next game state */
            GameState current_game_state = id_to_game_state.get(Integer.parseInt(game_state_id)).clone();
            /* Clone the planner so that we can simulate */

            GameState next_game_state = null;
            try {
                //System.out.println("Action name: " + action_name);
                PlayerAction pa = prepareActionNonAbstract(action_name, current_game_state, player, min_max_player);
                //System.out.println(pa);
                current_game_state.issueSafe(pa);
                current_game_state.cycle();

                /* Should throw an error if null or action can't be executed go through */
                StringWriter buffer = new StringWriter();
                XMLWriter w = new XMLWriter(buffer, " ");
                pa.toxml(w);
                String tmp_action = buffer.toString();
                //id_to_player_action.put(current_action_id,pa);

                next_game_state = current_game_state;

                in_pipe.readLine();
                //out_pipe.write(Integer.toString(current_action_id) + "\n");
                out_pipe.write(tmp_action + "\n");
                out_pipe.flush();
                //current_action_id++;
            } catch (Exception e) {
                /* Player action or game state inconsistent...should never be here. */
                //e.printStackTrace();

                /* Need to send over failed for action */
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

    public ArrayList<Unit> checkUnitActivity(GameState gs) {
        ArrayList<Unit> avail_units = new ArrayList<Unit>();
        for (Unit u : gs.getUnits()) {
            if (u.getPlayer() != -1) {
                UnitAction uact = gs.getUnitAction(u);
                /* multiple units may be available */
                if ((uact == null || uact.getType() == UnitAction.TYPE_NONE)) {
                    avail_units.add(u);
                }
            }
        }
        if (avail_units.size() == 0) {
            return null;
        } else {
            /* Prioritize max's unit over min's unit */
            return avail_units;
        }
    }

    public GameState simulate(GameState gs) {
        boolean game_over = false;

        game_over = gs.cycle();
        if (game_over) {
            return gs;
        }

        while (checkUnitActivity(gs) == null) {
            game_over = gs.cycle();
            if (game_over) {
                return null;
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

            //System.out.println("Game state in simulateUntilNextChoicePoint: " + game_state);
            if ( game_state_id.equals("gameover") ) {
                /* Don't simulate... */
            }

            GameState current_game_state = id_to_game_state.get(Integer.parseInt(game_state_id)).clone();
            GameState next_game_state = simulate(current_game_state);

            sendGameState(next_game_state,true);

            SimpleSqrtEvaluationFunction3 eval = new SimpleSqrtEvaluationFunction3();
            double reward = eval.evaluate(player, 1 - player, next_game_state);

            in_pipe.readLine();
            out_pipe.write(reward + "\n");
            out_pipe.flush();

        } catch (Exception e) {
            System.out.println("GameState: " + game_state_id);
            e.printStackTrace();
        }
    }

    public void canIssueActions(int player) {
        /* Check who can start issuing actions */
        /* NOTE: If there are multiple available units, choose one at random... */
        try {
            String gs_string_id = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            GameState gs = id_to_game_state.get(Integer.parseInt(gs_string_id));
            String min_max = in_pipe.readLine();
            out_pipe.write("success\n");
            out_pipe.flush();

            ArrayList<Unit> units = checkUnitActivity(gs);
            if (units == null) {
                /* THIS SHOULD NEVER HAPPEN... */
                in_pipe.readLine();
                out_pipe.write("false\n");
                out_pipe.flush();
                return;
                //throw new Exception("THIS SHOULD NEVER HAPPEN");
            }

            switch (min_max) {
                case "min":
                    for (Unit u : units) {
                        /* Priortize min's units over max... */
                        if (u.getPlayer() == (1 - player)) {
                            in_pipe.readLine();
                            out_pipe.write("true\n");
                            out_pipe.flush();
                            return;
                        }
                    }

                    in_pipe.readLine();
                    out_pipe.write("false\n");
                    out_pipe.flush();
                    break;
                case "max":
                    for (Unit u : units) {
                        /* Priortize max's units over min... */
                        if (u.getPlayer() == player) {
                            in_pipe.readLine();
                            out_pipe.write("true\n");
                            out_pipe.flush();
                            return;
                        }
                    }

                    in_pipe.readLine();
                    out_pipe.write("false\n");
                    out_pipe.flush();
                    break;
                case "either":
                    for (Unit u : units) {
                        /* Priortize max's units over min... */
                        if (u.getPlayer() == player) {
                            in_pipe.readLine();
                            out_pipe.write("true\n");
                            out_pipe.flush();
                            return;
                        }
                    }

                    in_pipe.readLine();
                    out_pipe.write("false\n");
                    out_pipe.flush();
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public PlayerAction getNextActionNonAbstract(GameState gs) {
        try {
            String action;
            PlayerAction next_action = new PlayerAction();
            while ( !(action = in_pipe.readLine() ).equals("done") ) {
                /* Parse action */
//                List< Pair<Unit,UnitAction> > tmp = id_to_player_action.get(Integer.parseInt(action)).getActions();
//                for ( Pair<Unit,UnitAction> ua : tmp ) {
//                    if ( ua.m_a != null && (gs.getUnitAction(gs.getUnit(ua.m_a.getID())) == null) ) {
//                    //if ( ua.m_a != null && (gs.getUnitAction(ua.m_a) == null) ) {
//                        next_action.addUnitAction(ua.m_a, ua.m_b);
//                    }
//                }
                Element action_str = new SAXBuilder().build(new StringReader(action)).getRootElement();
                List< Pair<Unit,UnitAction> > tmp = PlayerAction.fromXML(action_str, gs, utt).getActions();
                for ( Pair<Unit,UnitAction> ua : tmp ) {
                    if ( ua.m_a != null && (gs.getUnitAction(ua.m_a) == null) ) {
                        next_action.addUnitAction(ua.m_a, ua.m_b);
                    }
                }
                out_pipe.write("success\n");
                out_pipe.flush();
            }
            out_pipe.write("success\n");
            out_pipe.flush();
            //System.out.println("Next action: " + next_action);
            return next_action;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void sendGoals() {
        try {
            in_pipe.readLine();
            out_pipe.write(cur_strategy + "," + cur_enemy_strategy + "\n");
            out_pipe.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public PlayerAction communicateWithCPP(GameState gs, int player) {
        /* There are a few commands that will be sent by the C++ side
         *   1) execute
         *   2) simulateUntilNextChoicePoint
         *   3) canIssueActions
         * */
        try {
            /* Wait for message */
            String message = in_pipe.readLine();
            //System.out.println("Command: " + message);
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
                case "currentState":
                    id_to_game_state.clear();
                    current_id = 0;
                    sendGameState(gs,true);
                    break;
                case "nextAction":
                    PlayerAction act = getNextActionNonAbstract(gs);
                    return (act == null) ? new PlayerAction() : act;
                case "goals":
                    sendGoals();
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
        return null;
    }

    public void sendActions(GameState gs, int player) {
        try {
            // wait for ack:
            in_pipe.readLine();

            /* Send over non-abstract actions */
            TraceEntry te = null;
            StringWriter buffer = new StringWriter();
            XMLWriter w = new XMLWriter(buffer, " ");

            if (gs.getTime() > 0) {
                w.tagWithAttributes("rts.TraceEntry", "time = \"" + gs.getTime() + "\"");
                prev_physicalgamestate.toxml(w);
                w.tag("actions");
                ArrayList<UnitActionAssignment> actions = getActionsOfOpponent(gs, player);
                for ( UnitActionAssignment uaa : actions ) {
                    Unit u = uaa.unit;
                    if ( uaa.action.getType() != UnitAction.TYPE_NONE &&  uaa.action.getType() != UnitAction.TYPE_MOVE ) {
                        w.tagWithAttributes("action", "unitID=\"" + u.getID() + "\"");
                        uaa.action.toxml(w);
                        w.tag("/action");
                    }
                }
                w.tag("/actions");
                w.tag("/" + "rts.TraceEntry");
            } else {
                te = new TraceEntry(gs.getPhysicalGameState().clone(), gs.getTime());
                te.toxml(w);
            }

            prev_physicalgamestate = gs.getPhysicalGameState().cloneKeepingUnits();

            buffer.append("\n");
            out_pipe.write(buffer.toString());
            out_pipe.flush();

            in_pipe.readLine();
            out_pipe.write("message\n");
            out_pipe.flush();
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    public ArrayList<UnitActionAssignment> getActionsOfOpponent(GameState gs, int player) {
        /* IF the game state is not changed until both players have sent their actions, then the current
            game state should have actions executed in the previous game state.
         */
        ArrayList<UnitActionAssignment> actions = new ArrayList<UnitActionAssignment>();
        HashMap<Unit,UnitActionAssignment> action_assignments = gs.getUnitActions();
        for ( Unit u : action_assignments.keySet() ) {
            /* Check to see if the unit is the player's or the opponents */
            if ( u.getPlayer() != player ) {
                UnitActionAssignment u_assignment = action_assignments.get(u);
                actions.add(u_assignment);
            }
        }
        return actions;

    }

    @Override
    public PlayerAction getAction(int player, GameState gs) throws Exception {
        //prev_simulate_gs = null;
//        long start_time = System.currentTimeMillis();
        sendActions(gs,player);
        actual_current_game_state = gs;
        PlayerAction act = null;

        id_to_player_action.clear();
        current_action_id = 0;

        while (true) {
            try {
                act = communicateWithCPP(gs, player);
                if (act != null) {
                    //System.out.println("Action found");
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        //System.out.println("Action being executed: " + act);
//        long end_time = System.currentTimeMillis();
//        System.out.println("Timing: " + (end_time-start_time));
        return act;
    }

    @Override
    public void preGameAnalysis(GameState gs, long milliseconds) throws Exception {
        // send the game state:
        out_pipe.append("preGameAnalysis " + milliseconds + "\n");

        XMLWriter w = new XMLWriter(out_pipe, " ");
        gs.toxml(w);
        w.flush();
        out_pipe.append("\n");
        out_pipe.flush();

        // wait for ack:
        in_pipe.readLine();
    }

    @Override
    public MicroCCG clone() {
        return new MicroCCG(TIME_BUDGET, ITERATIONS_BUDGET, server_address, server_port, utt);
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
        String message = in_pipe.readLine();
        out_pipe.write("gameover\n");
        out_pipe.flush();
    }
}