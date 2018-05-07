#include <set>
using std::set;
#include <vector>
using std::vector;
#include <string>
using std::string;
#include <map>
using std::map;

#include <ctime>
#include <iostream>
#include <cstdlib>
#include <deque>
#include <memory>
#include <utility>
#include <boost/asio.hpp>

#include "CommWrapper.hpp"
#include "rapidXML/rapidxml.hpp"
#include "rapidXML/rapidxml_print.hpp"

using boost::asio::ip::tcp;

void CommWrapper::getInitialInfo(string & budget) {
    //std::cout << "Acquiring initial budget and UTT: " << std::endl;

    budget = receiveMessage();

    //size_t length = socket_.read_some(boost::asio::buffer(data_,max_length), error);

    /* Budget: time_limit iteration_limit */
    //std::cout << "Budget: " << budget << std::endl;
    //std::cout.write(data_,length); 

    //boost::asio::write(socket_,boost::asio::buffer("ack\n",4),error);

    string utt = receiveMessage();

    //length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    //std::cout << "UTT: " << utt << std::endl;

    //boost::asio::write(socket_,boost::asio::buffer("ack\n",4),error);
}

vector< vector< std::pair< string, string > > > CommWrapper::readXML() {
    /* TODO: Read XML input from socket */
    vector< vector< std::pair< string, string > > > tmp;

    /* Read in state */
    size_t length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    std::cout << "Initial State: " << std::endl; std::cout.write(data_,length);

    /* Send ack */
    boost::asio::write(socket_,boost::asio::buffer("ack\n",4),error);

    /* Read in action */
    length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    std::cout << "Actions: " << std::endl; std::cout.write(data_,length);

    /* Send ack */
    boost::asio::write(socket_,boost::asio::buffer("ack\n",4),error);

    return tmp;
}

void CommWrapper::writeXML(vector< vector< std::pair< string, string > > > actions) {

    rapidxml::xml_document<> doc;
    rapidxml::xml_node<> *acts = doc.allocate_node(rapidxml::node_element,"actions");
    std::cout << std::endl;
    for ( auto action : actions ) {
        //std::cout << "action.first, action.second: " << action[0].first << "," << action[0].second << std::endl;

        rapidxml::xml_node<> *act = doc.allocate_node(rapidxml::node_element,"action");

        char *key = doc.allocate_string(action[0].first.c_str());
        char *val = doc.allocate_string(action[0].second.c_str());
        act->append_attribute(doc.allocate_attribute(key,val));
        rapidxml::xml_node<> *unitAct = doc.allocate_node(rapidxml::node_element,"UnitAction");
        for ( int i = 1; i < (int)action.size(); i++ ) {
            char *key = doc.allocate_string(action[i].first.c_str());
            char *val = doc.allocate_string(action[i].second.c_str());
            unitAct->append_attribute(doc.allocate_attribute(key,val));
        }
        act->append_node(unitAct);
        acts->append_node(act);
    }
    doc.append_node(acts);

    string xml2Str;
    rapidxml::print(std::back_inserter(xml2Str),doc);
    xml2Str.erase(std::remove(xml2Str.begin(), xml2Str.end(), '\n'), xml2Str.end());
    xml2Str.erase(std::remove(xml2Str.begin(), xml2Str.end(), '\t'), xml2Str.end());

    std::cout << xml2Str << "," << xml2Str.length() << std::endl;
    boost::asio::write(socket_,boost::asio::buffer(xml2Str,xml2Str.length()),error);
}

vector< vector< std::pair< string, string > > > CommWrapper::readXMLNonAbstract( map< string, map< string, string > > & curState, bool & isGameOver ) {
    vector< vector< std::pair< string, string > > > ret_actions;

    /* ret_actions:
        vector< std::pair< string , string > >:
        [0] -> "actname", action name
        [1:] -> key, value
    */

    /* Read in state and actions */
    boost::asio::write(socket_,boost::asio::buffer("actions\n",8),error);

    string actions = "";
    size_t length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    /* Maximum length is actually 8192. Need to re-read from buffer for data more than 8192 bytes */

    while (length == 8192) {
        string input(data_,length);
        actions = actions + input;
        length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    }

    string input(data_,length);
    actions = actions + input;

    /* Check to see if this is a game over */
    if ( actions == "gameover\n" ) {
        isGameOver = true;
        boost::asio::write(socket_,boost::asio::buffer("gameover\n",9),error);
        return ret_actions;
    }

    /* Extract the actions */
    rapidxml::xml_document<> doc;
    char *cActions = NULL;
    try {
        cActions = doc.allocate_string(actions.c_str());
    } catch ( const std::exception& e ) {
        std::cout << e.what();
        exit(0);
    }
    //std::cout << "Actions: " << actions << std::endl;

    doc.parse<0>(cActions);
    rapidxml::xml_node<> *traceEntry = doc.first_node();
    if ( traceEntry == NULL ) {
        std::cout << "Trace entry not found" << std::endl;
        boost::asio::write(socket_,boost::asio::buffer("notfound\n",9),error);
        return ret_actions;
    }
    /* Get initial state */
    /* Do I need anything else for the current state? */
    /* ID : vector< pair< key, value > > */
    //std::cout << actions << std::endl;

    rapidxml::xml_node<> *state = traceEntry->first_node();
    rapidxml::xml_node<> *units = state->first_node("units");
    for ( rapidxml::xml_node<> *unit = units->first_node(); unit; unit = unit->next_sibling() ) {
        string id = "";
        std::map< string , string > tmp;
        for (rapidxml::xml_attribute<> *attr = unit->first_attribute(); attr; attr = attr->next_attribute()) {
            string key(attr->name());
            string val(attr->value());
            if ( key == "ID" ) {
                id = val;
            } else {
                tmp.insert(std::make_pair(key,val));
            }
        }
        curState.insert(std::make_pair(id,tmp));
    }

    //std::cout << "Actions: " << actions << std::endl;

    /* Get actions */
    rapidxml::xml_node<> *acts= traceEntry->last_node();
    for ( rapidxml::xml_node<> *act = acts->first_node(); act; act = act->next_sibling() ) {
        vector< std::pair< string , string > > ret_act;
        rapidxml::xml_node<> *node = act->first_node(); 
        //std::cout << "UnitID: " << act->first_attribute()->value() << std::endl;
        if ( curState.find(act->first_attribute()->value()) != curState.end()) {
            string tmp = curState.at(act->first_attribute()->value()).at("type") + act->first_attribute()->value();
            ret_act.push_back( std::make_pair("unitID",tmp) );
        }
        for (rapidxml::xml_attribute<> *attr = node->first_attribute(); attr; attr = attr->next_attribute()) {
            string name(attr->name()); string val(attr->value());
            if ( name == "type" ) {
                ret_act.insert(ret_act.begin(),std::make_pair("actname",typeToAction[stoi(val)]) );
                continue;
            }
            if ( name == "parameter" ) {
                if ( stoi(val) != 10 ) { 
                    ret_act.push_back(std::make_pair(name,direction[stoi(val)]));
                }
                continue;
            }

            if ( name == "x" || name == "y") { continue; }

            ret_act.push_back(std::make_pair(name,val));

        }
        ret_actions.push_back(ret_act);
    }

    /* Send ack */
    sendMessage("actions");

    /* Send ack */
    //boost::asio::write(socket_,boost::asio::buffer("actions\n",8),error);

    return ret_actions;
}

vector< vector< std::pair< string, string > > > CommWrapper::readXMLAbstract( map< string, map< string, string > > & curState, bool & isGameOver ) {
    /* Question: Should we switch gameover and ret_actions? */
    /* Returned actions? */
    vector< vector< std::pair< string, string > > > ret_actions;

    /* ret_actions:
        vector< std::pair< string , string > >:
        [0] -> "actname", action name
        [1:] -> key, value
    */

    /* Read in state and actions */

    /* Send ack */
    boost::asio::write(socket_,boost::asio::buffer("actions\n",8),error);

    string actions = "";
    size_t length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    /* Maximum length is actually 8192. Need to re-read from buffer for data more than 8192 bytes */

    while (length == 8192) {
        string input(data_,length);
        actions = actions + input;
        length = socket_.read_some(boost::asio::buffer(data_,max_length), error);
    }

    string input(data_,length);
    actions = actions + input;

    /* Check to see if this is a game over */
    if ( actions == "gameover\n" ) {
        isGameOver = true;
        boost::asio::write(socket_,boost::asio::buffer("gameover\n",9),error);
        return ret_actions;
    }

    /* Extract the actions */
    rapidxml::xml_document<> doc;
    char *cActions = NULL;
    try {
        cActions = doc.allocate_string(actions.c_str());
    } catch ( const std::exception& e ) {
        std::cout << e.what();
        exit(0);
    }
    doc.parse<0>(cActions);
    rapidxml::xml_node<> *traceEntry = doc.first_node();
    if ( traceEntry == NULL ) {
        std::cout << "Trace entry not found" << std::endl;
        boost::asio::write(socket_,boost::asio::buffer("notfound\n",9),error);
        return ret_actions;
    }
    /* Get initial state */
    /* Do I need anything else for the current state? */
    /* ID : vector< pair< key, value > > */
    //std::cout << actions << std::endl;

    rapidxml::xml_node<> *initState = traceEntry->first_node();
    rapidxml::xml_node<> *units = initState->first_node("units");
    for ( rapidxml::xml_node<> *unit = units->first_node(); unit; unit = unit->next_sibling() ) {
        string id = "";
        std::map< string , string > tmp;
        for (rapidxml::xml_attribute<> *attr = unit->first_attribute(); attr; attr = attr->next_attribute()) {
            string key(attr->name());
            string val(attr->value());
            if ( key == "ID" ) {
                id = val;
            } else {
                tmp.insert(std::make_pair(key,val));
            }
        }
        curState.insert(std::make_pair(id,tmp));
    }

    /* Get actions */
    rapidxml::xml_node<> *absacts= traceEntry->last_node();
    for ( rapidxml::xml_node<> *absact = absacts->first_node(); absact; absact = absact->next_sibling() ) {
        /* Get abstractaction */
        vector< std::pair< string , string > > ret_act;
        rapidxml::xml_node<> *node = absact->first_node();
        string actname(node->name());
        //if ( actname != "Attack" and actname != "Train" ) {
        ret_act.insert(ret_act.begin(),std::make_pair("actname",actname) );
        //}
        for (rapidxml::xml_attribute<> *attr = node->first_attribute(); attr; attr = attr->next_attribute()) {
            string name(attr->name());
            string val(attr->value());

            //if ( actname == "Attack" ) {
            //    if ( name == "unitID" ) {
            //        string unitType = curState.at(val).at("type");
            //        ret_act.insert(ret_act.begin(), std::make_pair("actname",actname + unitType ) );
            //    }
            //} else {
            //    if ( actname == "Train" ) {
            //        if ( name == "type" ) {
            //            ret_act.insert(ret_act.begin(),std::make_pair("actname",actname + val) );
            //        }
            //    }
            //}

            if ( name == "x" || name == "y") { continue; }

            if ( curState.find(val) == curState.end() ) {
                ret_act.push_back( std::make_pair(name,val) );
            } else {
                string tmp = curState.at(val).at("type") + val;
                ret_act.push_back( std::make_pair(name,tmp) );
            }
        }
        ret_actions.push_back(ret_act);
    }

    /* Send ack */
    sendMessage("actions");

    return ret_actions;
}

void CommWrapper::writeXMLAbstract(vector< vector< std::pair< string, string > > > actions) {
    /* NOTE: This function is only used as the adversarial generator's state transition function */

    /* Structure of actions data structure:
        actions[i] -> action
        actions[i][0] -> "unitID",unitId
        actions[i][1] -> "actname",action_name
        actions[i][2:] -> parameter,value
    */
    
    rapidxml::xml_document<> doc;
    rapidxml::xml_node<> *acts = doc.allocate_node(rapidxml::node_element,"abstractactions");

    for ( auto action : actions ) {

        rapidxml::xml_node<> *act = doc.allocate_node(rapidxml::node_element,"abstractaction");
        char *key = doc.allocate_string(action[0].first.c_str());
        char *val = doc.allocate_string(action[0].second.c_str());

        act->append_attribute(doc.allocate_attribute(key,val));

        char *abs_act = doc.allocate_string(action[1].second.c_str());
        rapidxml::xml_node<> *unit_act = doc.allocate_node(rapidxml::node_element,abs_act);

        for ( int i = 2; i < (int)action.size(); i++ ) {
            char *key = doc.allocate_string(action[i].first.c_str());
            char *val = doc.allocate_string(action[i].second.c_str());
            unit_act->append_attribute(doc.allocate_attribute(key,val));
        }
        act->append_node(unit_act);
        acts->append_node(act);
    }
    doc.append_node(acts);

    string xml_to_str;
    rapidxml::print(std::back_inserter(xml_to_str),doc);

    xml_to_str.erase(std::remove(xml_to_str.begin(), xml_to_str.end(), '\n'), xml_to_str.end());
    xml_to_str.erase(std::remove(xml_to_str.begin(), xml_to_str.end(), '\t'), xml_to_str.end());

    //std::cout << xml_to_str << "," << xml_to_str.length() << std::endl;
    boost::asio::write(socket_,boost::asio::buffer(xml_to_str,xml_to_str.length()),error);

}
