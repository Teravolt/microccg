#ifndef __BOT_H__
#define __BOT_H__

/* Base class for bot. To create a microRTS agent, use this as a base class */

#include <boost/asio.hpp>
using boost::asio::ip::tcp;

#include "CommWrapper.hpp"

class Bot {

    public:
        inline Bot( boost::asio::io_service& io_service, tcp::endpoint endpoint) : acceptor_(io_service, endpoint) ,socket_(io_service) {}

        virtual void startAI() { std::cout << "Please override this..." << std::endl; }

        /* Inheriting class would need to initialize these */
        inline void sendActions(vector< vector< std::pair< string , string > > > actions) { comm->writeXMLAbstract(actions); }

        inline ~Bot() {
            if ( comm != NULL ) { delete comm; }
        }

    protected:

        tcp::acceptor acceptor_;
        tcp::socket socket_;
        CommWrapper *comm = NULL;

        inline void initialize() {
            accept();
            comm = new CommWrapper(std::move(socket_));
            startAI();
        }

        inline void accept() { acceptor_.accept(socket_); }

};

#endif
