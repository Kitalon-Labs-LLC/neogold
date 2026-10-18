# NeoGold Training Directory Makefile (TC-OPS-07, TC-EST-05)

.PHONY: all reproduce test clean

PYTHON ?= python3

all: reproduce

reproduce:
	cd .. && PYTHONPATH=training:. $(PYTHON) training/reproduce.py

test:
	cd .. && PYTHONPATH=training:. pytest training/tests -v

clean:
	rm -rf output
