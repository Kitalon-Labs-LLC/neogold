# NeoGold Training & Verification Makefile (TC-OPS-07, TC-EST-05)

.PHONY: all reproduce test docker-build docker-reproduce clean

PYTHON ?= python3

all: reproduce

reproduce:
	PYTHONPATH=training:. $(PYTHON) training/reproduce.py

test:
	PYTHONPATH=training:. pytest training/tests -v

docker-build:
	docker build -t neogold-train:latest -f training/Dockerfile .

docker-reproduce: docker-build
	docker run --rm --network none neogold-train:latest

clean:
	rm -rf training/output
