package config

import (
	"encoding/json"
	"errors"
	"io"
	"os"
)

type Config struct {
	Port string   `json:"port"`
	DB   DbConfig `json:"db"`
}

type DbConfig struct {
	Url string `json:"url"`
}

func LoadConfig() Config {
	config := new(Config)
	file, err := os.Open("./config/config.json")

	if err != nil {
		panic(errors.New("Failed to open config file: " + err.Error()))

	}
	defer file.Close()

	byteValue, err := io.ReadAll(file)

	if err != nil {
		panic(errors.New("Failed to read config file: " + err.Error()))

	}
	err = json.Unmarshal(byteValue, config)

	if err != nil {
		panic(errors.New("Failed to load config file: " + err.Error()))

	}

	return *config
}
