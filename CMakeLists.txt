cmake_minimum_required(VERSION 2.8)

project(MicroRTSBot)

#set(ELEXIR_DIR "/Users/pavankantharaju/Documents/drexel_university/research/research-code/elexir-master/upgrades/elexir" )
#set(ELEXIR_DIR "/Users/pavankantharaju/Documents/drexel_university/research/research-code/elexir/" )
set(ELEXIR_DIR "INSERT-DIRECTORY-OF-ELEXIR" )

find_package( Boost REQUIRED COMPONENTS thread program_options system )

include(ExternalProject)
ExternalProject_Add(
  libjemalloc-build
  SOURCE_DIR ${ELEXIR_DIR}/jemalloc-5.1.0/
  CONFIGURE_COMMAND ${ELEXIR_DIR}/jemalloc-5.1.0/configure --prefix=${ELEXIR_DIR}/jemalloc-5.1.0/ --enable-cc-silence
  PREFIX ${ELEXIR_DIR}/jemalloc-5.1.0/
  INSTALL_COMMAND echo
  BUILD_COMMAND make
  BUILD_IN_SOURCE 1
)

#ExternalProject_Add(
#  libjemalloc-build
#  SOURCE_DIR ${ELEXIR_DIR}/jemalloc-4.1.0/
#  CONFIGURE_COMMAND ${ELEXIR_DIR}/jemalloc-4.1.0/configure --prefix=${Elexir_PATH}/jemalloc-4.1.0/ --enable-cc-silence
#  PREFIX ${ELEXIR_DIR}/jemalloc-4.1.0/
#  INSTALL_COMMAND echo
#  BUILD_COMMAND make
#  BUILD_IN_SOURCE 1
#)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11") #-lboost_system -lboost_date_time -lboost_thread")

include_directories( ${ELEXIR_DIR}/LexCore )
include_directories( ${Boost_INCLUDE_DIRS} )
include_directories( ${ELEXIR_DIR} )
include_directories( lib/rapidxml )
include_directories( lib )

add_library(libjemalloc SHARED IMPORTED)
set_target_properties(libjemalloc PROPERTIES IMPORTED_LOCATION "${ELEXIR_DIR}/jemalloc-5.1.0/lib/libjemalloc.a")
add_dependencies(libjemalloc libjemalloc-build)

#add_library(libjemalloc SHARED IMPORTED)
#set_target_properties(libjemalloc PROPERTIES IMPORTED_LOCATION "${ELEXIR_DIR}/jemalloc-4.1.0/lib/libjemalloc.a")
#add_dependencies(libjemalloc libjemalloc-build)

add_library(LexCore SHARED IMPORTED )
set_target_properties(LexCore PROPERTIES IMPORTED_LOCATION "${ELEXIR_DIR}/LexCore/libLexCore.a")

set(LEXADGEN_FILES
    ${ELEXIR_DIR}/adversarial-generate/plantree.hpp
    ${ELEXIR_DIR}/adversarial-generate/node.hpp
    ${ELEXIR_DIR}/adversarial-generate/adversarialgen.cpp
    ${ELEXIR_DIR}/adversarial-generate/adversarialgen.hpp
    ${ELEXIR_DIR}/adversarial-generate/networkedsimulation.hpp
    ${ELEXIR_DIR}/adversarial-generate/mrts_simulation.hpp
)

set(LIB_FILES
    lib/CommWrapper.cpp
)

add_executable( microCCG main.cpp ${LIB_FILES} ${LEXADGEN_FILES} )
target_link_libraries( microCCG LexCore )
target_link_libraries( microCCG libjemalloc )
target_link_libraries( microCCG ${Boost_LIBRARIES} ${CMAKE_THREAD_LIBS_INIT} )
