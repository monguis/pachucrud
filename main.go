package main

import (
	"context"
	"fmt"
	"log"
	"net"

	"github.com/jackc/pgx/v5/pgxpool"
	throwPb "github.com/monguis/pachuco-proto/dice-throws_protos"
	userPb "github.com/monguis/pachuco-proto/user_protos"
	"github.com/monguis/pachucrud/config"
	"google.golang.org/grpc"
)

type server struct {
	userPb.UserServer
	throwPb.DiceThrowServer
}

func (s *server) GetUser(ctx context.Context, req *userPb.UserIdRequest) (*userPb.UserResponse, error) {
	log.Printf("Received request from: %s", req.Id)
	return &userPb.UserResponse{Nickname: fmt.Sprintf("Hello, %s!", "Frederers")}, nil
}

func main() {
	// Start a TCP listener
	conf := config.LoadConfig()

	listener, err := net.Listen("tcp", ":"+conf.Port)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	dbpool, err := pgxpool.New(context.Background(), conf.DB.Url)
	if err != nil {
		log.Fatalf("Unable to connect to database: %v\n", err)
	}
	defer dbpool.Close()
	log.Println("DB Connection established")

	grpcServer := grpc.NewServer()
	userPb.RegisterUserServer(grpcServer, &server{})
	throwPb.RegisterDiceThrowServer(grpcServer, &server{})

	log.Println("gRPC server listening on port " + conf.Port)
	if err := grpcServer.Serve(listener); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}
