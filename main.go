package main

import (
	"context"
	"fmt"
	"log"
	"net"

	pb "github.com/monguis/pachuco-proto/user_protos"
	"github.com/monguis/pachucrud/config"
	"google.golang.org/grpc"
)

type server struct {
	pb.UserServer
}

func (s *server) GetUser(ctx context.Context, req *pb.IdRequest) (*pb.UserResponse, error) {
	log.Printf("Received request from: %s", req.Id)
	return &pb.UserResponse{Nickname: fmt.Sprintf("Hello, %s!", "Frederers")}, nil
}

func main() {
	// Start a TCP listener
	conf := config.LoadConfig()

	listener, err := net.Listen("tcp", ":"+conf.Port)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	// Create a new gRPC server
	grpcServer := grpc.NewServer()
	pb.RegisterUserServer(grpcServer, &server{})

	log.Println("gRPC server listening on port " + conf.Port)
	if err := grpcServer.Serve(listener); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}
