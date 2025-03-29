package main

import (
	"context"
	"fmt"
	"log"
	"net"

	"github.com/monguis/pachucrud/config"
	pb "github.com/monguis/pachucrud/proto"
	"google.golang.org/grpc"
)

type server struct {
	pb.UnimplementedGreeterServer
}

func (s *server) SayHello(ctx context.Context, req *pb.HelloRequest) (*pb.HelloResponse, error) {
	log.Printf("Received request from: %s", req.Name)
	return &pb.HelloResponse{Message: fmt.Sprintf("Hello, %s!", req.Name)}, nil
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
	pb.RegisterGreeterServer(grpcServer, &server{})

	log.Println("gRPC server listening on port " + conf.Port)
	if err := grpcServer.Serve(listener); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}
