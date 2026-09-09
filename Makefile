.PHONY: start stop build test integration-test stress benchmark compare reset-db plots replicas failure-test

start:
	docker compose up -d --build db safe
	./scripts/wait-ready.sh http://localhost:8080/actuator/health/readiness

build:
	docker compose build safe

test:
	docker build --target test-runner -t burst-safe-inventory-tests .
	docker compose up -d db
	docker run --rm --network burst-safe-inventory_default \
		-e TEST_DB_URL=jdbc:postgresql://db:5432/inventory burst-safe-inventory-tests

integration-test: test

failure-test:
	./scripts/failure-scenarios.sh

stress:
	python3 stress/stress.py --mode safe --urls http://localhost:8080 --concurrency 100 --trials 10 --assert-safe

benchmark:
	python3 stress/stress.py --mode safe --urls http://localhost:8080 --concurrency 1,10,50,100,250,500 --assert-safe

compare:
	NAIVE_DELAY_MS=$${NAIVE_DELAY_MS:-10} docker compose --profile naive up -d --build db safe naive
	./scripts/wait-ready.sh http://localhost:8080/actuator/health/readiness
	./scripts/wait-ready.sh http://localhost:8081/actuator/health/readiness
	python3 stress/compare.py

replicas:
	docker compose --profile replicas up -d --build db safe safe-replica
	./scripts/wait-ready.sh http://localhost:8080/actuator/health/readiness
	./scripts/wait-ready.sh http://localhost:8082/actuator/health/readiness
	python3 stress/stress.py --mode safe --urls http://localhost:8080,http://localhost:8082 --concurrency 100,250 --trials 5 --assert-safe

reset-db:
	docker compose exec -T db psql -U inventory -d inventory -c "TRUNCATE reservations, items RESTART IDENTITY CASCADE"

plots:
	@test -n "$(CSV)" || (echo "usage: make plots CSV=results/.../comparison.csv"; exit 2)
	python3 stress/plot.py "$(CSV)" "$${CSV%.csv}.svg"

stop:
	docker compose --profile naive --profile replicas down
