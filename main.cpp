#include <set>
using std::set;
#include <vector>
using std::vector;
#include <string>
using std::string;

#include <iostream>
#include <boost/asio.hpp>
using boost::asio::ip::tcp;

#include "microRTSBot.hpp"

int main(int argc, char* argv[]) {
    try {
        if (argc != 3) {
          std::cerr << "Usage: microRTSBot <port> <planning file>" << std::endl;;
          return 1;
        }

        boost::asio::io_service io_service;
        tcp::resolver resolver(io_service);
        std::string address = "127.0.0.1"; std::string port = argv[1];
        std::string planning_file = argv[2];

        tcp::resolver::query query(address,port);
        tcp::endpoint endpoint = *resolver.resolve(query);

        std::cout << "Starting service" << std::endl;

        MicroRTSBot bot(io_service, endpoint, planning_file);

    } catch (std::exception& e) {
        std::cerr << "Exception: " << e.what() << "\n";
    }
    return 0;
}
