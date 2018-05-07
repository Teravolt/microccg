#ifndef __MICRORTS_BOT_H__
#define __MICRORTS_BOT_H__

#include<vector>
#include<deque>
#include<string>
#include<map>
using std::string;
using std::vector;
using std::deque;
using std::map;

#include "lib/CommWrapper.hpp"
#include "lib/bot.hpp"
#include "recognize/recognizer.h"
#include "adversarial-generate/adversarialgen.hpp"
#include "LexCore/driver.h"
#include "LexCore/problem.h"

#include "LexCore/categoryDef.h"
#include "LexCore/atom.h"
using lexCore::CategoryDef;
using lexCore::Atom;

#define EPSILON 0.0000000000000000000001

class MicroRTSBot : Bot {

    public:

        /* Overrode base class implementation */
        void startAI() {

            std::random_device rd;
            std::mt19937 gen(rd());

            /* Get Unit Type Table and budget (NOTE: Do we even need the utt?) */
            string budget;
            comm->getInitialInfo(budget);
            timeLimit = std::stoi(budget.substr(budget.find(" ")+1,budget.find(",")-budget.find(" ")-1));  
            timeLimit -= 10;

            if ( DEBUGGING > 0 ) { std::cout << "Time per frame: " << timeLimit << std::endl; }

            /* Start up Planner and Plan Recognizer here */
            if ( genAPI == NULL ) {
                if ( DEBUGGING > 0 ) { std::cout << "Starting Adversarial Generator" << std::endl; }
                genAPI = new lexAdGen::AdversarialGenerator(&planLexicon,&recLexicon,comm,timeLimit);
            }

            /* Start reading from MicroRTS */
            while ( 1 ) {

                genAPI->start = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();

                /* Check to see if we reached the end of the game */
                bool isGameOver = false;
                map< string, map< string, string > > curState;
                vector< vector< std::pair<string, string > > > actions = comm->readXMLNonAbstract( curState, isGameOver );

                if ( isGameOver ) { break; }

                /* Even if we don't read any actions, we still want to parse the plan to get a more accurate parse */
                for ( auto act : actions ) {
                    string tmp = formatAction(act); 
                    if ( DEBUGGING > 0 ) { std::cout << "Action: " << tmp << std::endl; }
                    trace.push_back(tmp);
                }

                /* Add any objects that are not in the grammar (mostly units) */
                for ( auto unit : curState ) {
                    string tmp = unit.second.at("type") + unit.first;
                    for ( int i = 0; i < (int)tmp.size(); i++ ) { tmp[i] = tolower(tmp[i]); }

                    lexCore::Atom u = lexCore::Atom::addSymbol(tmp.c_str(), tmp.length());

                    if ( std::find(recLexicon.objects.begin(),recLexicon.objects.end(),u) == recLexicon.objects.end() ) {
                        /* Add to lexicon */
                        vector< lexCore::Atom > *unitType = new vector< lexCore::Atom >(); unitType->push_back(unitAtom);
                        recLexicon.addObj(u,unitType);
                    }
                }

                /* Send observed actions to recognizer and recognize. Note: Need to at least have 2 actions in the observation stream */

                /* Sliding window for recognition*/
                if ( trace.size() >= 3 ) {
                    /* If greater than or equal to three, pop off front */
                    trace.pop_front();
                }

                if ( trace.size() > 1 ) { recognize(gen); }

                /* Issue: What if we don't recognize a strategy? */
                if ( bestGoal == "None" ) {
                    genAPI->startPlanningProcess("Win",goals[0]); //goals[dist2(gen)]);
                } else {
                    genAPI->startPlanningProcess("Win",bestGoal);
                }
            }
        }

        inline void recognize(std::mt19937 gen) {
            ostringstream o;
            int size = (int)trace.size() < 3 ? (int)trace.size() : 3;
            o << "testName: microRTS;\n"; o << "initialState: [  ];\n"; o << "observations: [ ";
            for ( int j = 0; j < size; j++ ) {
                (j == 0) ? o << trace[j] : o << ", " << trace[j];
            }
            o << " ];\n";

            ostringstream strats;
            strats << "Rush";
            o << "query: [ ";
            o << queryString;
            o << " ];\n";

            if ( DEBUGGING > 0 ) {  std::cout << "Query: " << o.str() << std::endl; }
            
            /* Send to recognizer */
            recDriver->parse_string(o.str().c_str());

            if ( DEBUGGING > 0 ) { std::cout << "***RECOGNIZING***" << std::endl; }
            recAPI->processProblemMCTSOptimized(50,timeLimit);

            /* 
                Find the explanation that contains the highest reward: ( Is this reasonable? )
                Next, check the roots for the strategies. 
            */
            /* What happens if two children have the same reward? (random choice) */
    
            lexCore::MCTS *mcts = recAPI->getMCTS();
            lexCore::Node *nodePtr = mcts->getRoot();
            while ( (nodePtr != NULL) && (int)nodePtr->children.size() != 0 ) {
                lexCore::Node *tmpPtr = NULL;
                double maxReward = -1.0; 
                vector< lexCore::Node * > tiedNodes;
                for ( auto c : nodePtr->children ) {
                    if ( c->reward > maxReward ) {
                        maxReward = c->reward;
                        tiedNodes.clear();
                        tiedNodes.push_back(c);
                    } else {
                        /* Check for a tie */
                        if ( std::abs(c->reward - maxReward) < EPSILON  ) {
                            tiedNodes.push_back(c);
                        }
                    }
                }
                /* Choose a random child in the case of a tie */
                std::uniform_int_distribution<int> dist2(0,(int)tiedNodes.size()-1);
                nodePtr = tiedNodes[dist2(gen)];
            }

            string recognizedGoal = "None";

            vector< string > allGoals;
            for ( auto r : nodePtr->exp->roots ) {
                if ( std::find(goals.begin(),goals.end(),r->getCategoryKey()) != goals.end() ) {
                    if ( DEBUGGING > 0 ) { std::cout << "Goal found in roots of explanation: " << r->getCategoryFull() << std::endl; }
                    allGoals.push_back(*(r->getDef()->getName().getString()));
                }
            }

            if ( allGoals.size() > 0 ) {
                std::uniform_int_distribution<int> dist2(0,(int)allGoals.size()-1);
                recognizedGoal = allGoals[dist2(gen)];
            }
            
            if ( recognizedGoal == "None" ) {
                std::map< lexCore::Atom, lexCore::QueryResult > query = mcts->getQuery(nodePtr->obsIndex);
                /* Find the highest probability goal */
                if ( DEBUGGING > 0 ) { std::cout << "------------------------------------" << std::endl; }
                double totalProb = 0.0;
                for ( auto qit : query ) { totalProb += qit.second.cp; }
                double highestProb = 0.0;
                vector< string > tiedGoals;
                for ( auto qit : query ) {
                    if ( DEBUGGING > 0 ) { std::cout << "qit.first: " << *(qit.first.getString()) << ", qit.second: " << qit.second.cp << std::endl; }
                    double tmp = qit.second.cp / totalProb;
                    if ( tmp > highestProb ) {
                        highestProb = tmp;
                        tiedGoals.clear();
                        tiedGoals.push_back(*(qit.first.getString()));
                    } else {
                        if ( std::abs(tmp - highestProb) < EPSILON  ) {
                            tiedGoals.push_back(*(qit.first.getString()));
                        }
                    }
                }
                /* Choose a random goal? */
                std::uniform_int_distribution<int> dist2(0,(int)tiedGoals.size()-1);
                recognizedGoal = tiedGoals[dist2(gen)];
                if ( DEBUGGING > 0 ) { 
                    std::cout << "Best goal: " << bestGoal << " with probability: " << highestProb << std::endl;
                    std::cout << "------------------------------------" << std::endl;
                }
            }

            /* Goal hasn't changed, and goal has at least been seen. */
            if ( recognizedGoal != bestGoal && recognizedGoal != "None" ) {
                if ( DEBUGGING > 0 ) { std::cout << "New goal found: " << recognizedGoal << std::endl; }
                bestGoal = recognizedGoal;
            }            

            /* Reset MCTS (NOTE: Doesn't destroy MCTS..just the tree) */
            recAPI->resetMCTS();
            prob.reset();
        }

        inline MicroRTSBot( boost::asio::io_service& io_service, tcp::endpoint endpoint, string recognition_file, string planning_file) : Bot(io_service,endpoint) {
            
            /* NOTE: If we were to extend the agent to also learn while playing, we would need to initialize the learning component here. */
            string unit = "unit";
            unitAtom = lexCore::Atom::addSymbol(unit.c_str(), unit.length());
            prob.mdcpFlag = true; prob.mode = 0; prob.printFlag = false;
            rec_file = recognition_file;
            plan_file = planning_file;
            planDriver = new lexCore::Driver(prob, planLexicon);
            planDriver->parse_file( plan_file.c_str() );

            recDriver = new lexCore::Driver(prob, recLexicon);
            recDriver->parse_file( rec_file.c_str() );
            recAPI = new lexRec::Recognizer(&prob,&recLexicon);

            map< Atom, CategoryDef* >* allCats = recLexicon.getCats();

            /* Get all goals (i.e. category definitions) */
            ostringstream o;
            int i = 0;
            for ( auto catDef : *allCats ) {
                if ( std::find(categoryRemoved.begin(),categoryRemoved.end(),*(catDef.first.getString())) == categoryRemoved.end() 
                    && catDef.first.getString()->find("_SG_") == string::npos 
                    && recLexicon.resCatLookup(catDef.first) != NULL ) {
                    goals.push_back(*(catDef.first.getString()));
                    (i == 0) ? o << *(catDef.first.getString()) : o << "," << *(catDef.first.getString());   
                    i++;
                }
            }
            queryString = o.str();

            //finishedRec = false;

            if ( DEBUGGING > 0 ) { std::cout << "Starting MicroRTS AI" << std::endl; }
            initialize();
        }

        inline ~MicroRTSBot() {
            if ( recAPI != NULL ) { delete recAPI; }
            if ( recDriver != NULL ) { delete recDriver; }
            if ( planDriver != NULL ) { delete planDriver; }
            if ( genAPI != NULL ) { delete genAPI; }
        }

        inline string formatAction(vector< std::pair<string , string > > & action) {
            std::ostringstream act;
            string actname = action[0].second;
            for ( int i = 0; i < (int)action[0].second.size(); i++ ) { action[0].second[i] = tolower(action[0].second[i]); }
            act << action[0].second << "(";
            for ( int i = 1; i < (int)action.size(); i++ ) {
                for ( int j = 0; j < (int)action[i].second.size(); j++ ) { action[i].second[j] = tolower(action[i].second[j]); }
                i == 1 ? (act << action[i].second) : act << "," << action[i].second;
            }
            act << ")";
            return act.str();
        }

    private:

        int DEBUGGING = 0;

        /* Variables for recognition */
        deque< string > trace;

        //bool finishedRec;

        string rec_file;
        string plan_file;

        lexCore::Atom unitAtom;
        vector<string> goals;
        string bestGoal = "None";
        int timeLimit = -1;
        
        string queryString = ""; 

        lexCore::Problem prob;

        lexCore::Lexicon recLexicon;
        lexCore::Lexicon planLexicon;

        lexCore::Driver *recDriver;
        lexCore::Driver *planDriver;

        lexRec::Recognizer *recAPI;
        lexAdGen::AdversarialGenerator *genAPI;

        vector<string> categoryRemoved = { "moveC", "produceC", "attackC", "returnC", "harvestC" };

};

#endif
