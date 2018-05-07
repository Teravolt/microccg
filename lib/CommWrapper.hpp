#ifndef __COMMWRAPPER_H__
#define __COMMWRAPPER_H__

#include <set>
using std::set;
#include <vector>
using std::vector;
#include <string>
using std::string;
#include <map>
using std::map;

#include <cstdlib>
#include <deque>
#include <iostream>
#include <memory>
#include <utility>
#include <boost/asio.hpp>

using boost::asio::ip::tcp;

class CommWrapper : public std::enable_shared_from_this< CommWrapper > {

    public:
        /* Read and write actions and current state */
        inline CommWrapper(tcp::socket socket) : socket_(std::move(socket)) { }

        /* Used for low-level actions */
        vector< vector< std::pair< string, string > > > readXML();
        void writeXML(vector< vector< std::pair<string,string> > > actions);

        /* Used for high-level actions */
        /* Returns actions, current state, and checks game over */
        vector< vector< std::pair< string, string > > > readXMLNonAbstract( map< string, map< string, string > > & curState, bool & isGameOver );

        vector< vector< std::pair< string, string > > > readXMLAbstract(map< string, map< string, string > > & curState, bool & isGameOver );
        void writeXMLAbstract(vector< vector< std::pair<string,string> > > actions);
    
        /* Get initial budget and UTT from MicroRTS (synchronous, blocking calls) */
        void getInitialInfo(string & budget);

        inline string getGameState() {
            boost::asio::write(socket_,boost::asio::buffer("gamestate\n",10),error);

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
            return actions; 
        }

        inline void sendGameState(string gameState) {
            if ( gameState.size() < max_length-1 ) {
                boost::asio::write(socket_,boost::asio::buffer(gameState + "\n",gameState.size()+1),error);
            } else {
                int i = 0;
                long rem = gameState.size() % max_length;
                while ( i < (int)gameState.size()-rem ) {
                    boost::asio::write(socket_,boost::asio::buffer(gameState.substr(i,max_length),max_length),error);
                    i += max_length;
                }
                rem ?  boost::asio::write(socket_,boost::asio::buffer(gameState.substr(i,rem) + "\n",rem+1),error) : boost::asio::write(socket_,boost::asio::buffer("\n",1),error);
            }
         
            socket_.read_some(boost::asio::buffer(data_,max_length), error);
        }

        inline void sendMessage(string msg) {
            /* Note: is this the right way to do this */
            boost::asio::write(socket_,boost::asio::buffer(msg + "\n",(int)msg.size() + 1),error);
            size_t length = socket_.read_some(boost::asio::buffer(data_,max_length), error); /* Enough to get back what we sent */
            string tmp(data_,length);
            if ( DEBUGGING > 0 ) { std::cout << "Received ack: " << tmp << std::endl; }
        }

        inline string receiveMessage() {
        
            boost::asio::write(socket_,boost::asio::buffer("receive\n",8),error);
            size_t length = socket_.read_some(boost::asio::buffer(data_,max_length), error); 
            string msg(data_,length);
            if ( DEBUGGING > 0 ) { std::cout << "Received msg: " << msg << std::endl; }
            return msg;
        }

    private:
        int DEBUGGING = 0;

        tcp::socket socket_;
        boost::system::error_code error;
        enum { max_length = 8192 };  /* Limit is actually 8192, so a large max length actually doesn't matter */
        char data_[max_length];

        /* Used for converting XML actions to ELEXIR actions */
        vector<string> typeToAction = { "idle", "move", "harvest", "return", "produce", "attack" };
        vector<string> direction = { "up", "right", "down", "left" };
};

#endif
