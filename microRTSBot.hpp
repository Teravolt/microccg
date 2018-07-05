#ifndef __MICRORTS_BOT_H__
#define __MICRORTS_BOT_H__

#include<vector>
#include<deque>
#include<string>
#include<map>
#include<random>
using std::string;
using std::vector;
using std::deque;
using std::map;

#include "lib/CommWrapper.hpp"
#include "lib/bot.hpp"
#include "adversarial-generate/adversarialgen.hpp"

#include "LexCore/driver.h"
#include "LexCore/problem.h"
#include "LexCore/categoryDef.h"
#include "LexCore/atom.h"

#define EPSILON 0.0000000000000000000001

class MicroRTSBot : Bot {

    public:

        /* Overrode base class implementation */
        void startAI() {

            /* Get Unit Type Table and budget (NOTE: Do we even need the utt?) */
            string budget;
            comm->getInitialInfo(budget);
            timeLimit = std::stoi(budget.substr(budget.find(" ")+1,budget.find(",")-budget.find(" ")-1));  
            timeLimit -= 10;

            if ( DEBUGGING > 0 ) { std::cout << "Time per frame: " << timeLimit << std::endl; }

            /* Start up Planner and Plan Recognizer here */
            if ( genAPI == NULL ) {
                if ( DEBUGGING > 0 ) { std::cout << "Starting Adversarial Generator" << std::endl; }
                genAPI = new lexAdGen::AdversarialGenerator(&planLexicon,&planLexicon,comm,timeLimit);
            }

            genAPI->con = 200;

            /* Start reading from MicroRTS */
            while ( 1 ) {

                string gameover = comm->receiveMessage();
                genAPI->start = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                if ( gameover == "gameover\n" ) { break; }
                genAPI->startPlanningProcess("Win","Win"); 
            }
        }

        inline MicroRTSBot( boost::asio::io_service& io_service, tcp::endpoint endpoint, string planning_file) : Bot(io_service,endpoint) {
            
            plan_file = planning_file;

            lexCore::Problem prob ( planLexicon );
            planDriver = new lexCore::Driver(prob, planLexicon);
            planDriver->parse_file( plan_file.c_str() );

            if ( DEBUGGING > 0 ) { std::cout << "Starting MicroRTS AI" << std::endl; }
            initialize();
        }

        inline ~MicroRTSBot() {
            if ( planDriver != NULL ) { delete planDriver; }
            if ( genAPI != NULL ) { delete genAPI; }
        }

    private:

        int DEBUGGING = 0;

        string plan_file;
        int timeLimit = -1;
        
        lexCore::Lexicon planLexicon;
        lexCore::Driver *planDriver = NULL;
        lexAdGen::AdversarialGenerator *genAPI = NULL;

};

#endif
