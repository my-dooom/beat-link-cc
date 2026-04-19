cmake_minimum_required(VERSION 3.16)
project(beat_link_cpp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Find gRPC and Protobuf ────────────────────────────────────────────────────
# Install via your package manager, e.g.:
#   macOS:  brew install grpc
#   Ubuntu: apt install -y libgrpc++-dev libprotobuf-dev protobuf-compiler-grpc
# Or build from source following https://grpc.io/docs/languages/cpp/quickstart/

find_package(Protobuf REQUIRED)
find_package(gRPC CONFIG REQUIRED)

# ── Proto compilation ─────────────────────────────────────────────────────────
set(PROTO_FILE "${CMAKE_CURRENT_SOURCE_DIR}/../proto/beat_link.proto")
set(PROTO_OUT  "${CMAKE_CURRENT_BINARY_DIR}/proto")
file(MAKE_DIRECTORY "${PROTO_OUT}")

# Locate the protoc and grpc_cpp_plugin executables.
find_program(PROTOC    protoc   REQUIRED)
find_program(GRPC_CPP_PLUGIN grpc_cpp_plugin
    HINTS "${gRPC_DIR}/../../../bin"
          "/usr/local/bin"
          "/usr/bin"
    REQUIRED)

# Generated source/header paths.
set(PROTO_SRCS "${PROTO_OUT}/beat_link.pb.cc")
set(PROTO_HDRS "${PROTO_OUT}/beat_link.pb.h")
set(GRPC_SRCS  "${PROTO_OUT}/beat_link.grpc.pb.cc")
set(GRPC_HDRS  "${PROTO_OUT}/beat_link.grpc.pb.h")

add_custom_command(
    OUTPUT  "${PROTO_SRCS}" "${PROTO_HDRS}" "${GRPC_SRCS}" "${GRPC_HDRS}"
    COMMAND "${PROTOC}"
            "--proto_path=${CMAKE_CURRENT_SOURCE_DIR}/../proto"
            "--cpp_out=${PROTO_OUT}"
            "--grpc_out=${PROTO_OUT}"
            "--plugin=protoc-gen-grpc=${GRPC_CPP_PLUGIN}"
            "${PROTO_FILE}"
    DEPENDS "${PROTO_FILE}"
    COMMENT "Generating C++ protobuf/gRPC stubs from beat_link.proto"
)
add_custom_target(proto_gen DEPENDS "${PROTO_SRCS}" "${PROTO_HDRS}" "${GRPC_SRCS}" "${GRPC_HDRS}")

# ── beat_link_client library ──────────────────────────────────────────────────
add_library(beat_link_client STATIC
    beat_link_client.cpp
    "${PROTO_SRCS}"
    "${GRPC_SRCS}"
)
add_dependencies(beat_link_client proto_gen)

target_include_directories(beat_link_client
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${PROTO_OUT}"
        "${Protobuf_INCLUDE_DIRS}"
)

target_link_libraries(beat_link_client
    PUBLIC
        gRPC::grpc++
        gRPC::grpc++_reflection
        protobuf::libprotobuf
)

# ── Example executable ────────────────────────────────────────────────────────
add_subdirectory(example)
