MicroCCG
========
MicroCCG is an adversarial CCG planner for the Real-Time Strategy (RTS) Game microRTS. This agent was developed to participate in the CIG 2018 microRTS tournament. Details about microRTS can be found on the [microRTS Github page](https://github.com/santiontanon/microrts). 

This agent has two main components: Adversarial CCG planner (C++) and microRTS bot (Java). For a more in-depth explanation of the agent, please see our paper titled: "microCCG, a CCG-based Game-Playing Agent for microRTS"

Requirements
============
ELEXIR (Engine for LEXicalized Intent Recognition)  
    - Paper: Delaying Commitment in Plan Recognition Using Combinatory Categorial Grammars  
    - Author: Christopher Geib  
    - Citation: Geib, C. W. 2009. Delaying commitment in plan recognition using combinatory categorial grammars. In Proceedings of the 21st International Joint Conference on Artifical Intelligence, IJCAI’09, 1702–1707. San Francisco, CA, USA: Morgan Kaufmann Publishers Inc.   

The ELEXIR system is currently avaiable upon request, but I have provided an executable called *microCCG* created on Ubuntu 16.04 64bit.
 
Development.
============
```
Makefile:
    rm ./CMakeCache.txt
    cmake -D CMAKE_BUILD_TYPE=Debug Unix .
 
XCode Project:
    rm ./CMakeCache.txt
    cmake -D CMAKE_BUILD_TYPE=Debug -G"Xcode" . 
```
