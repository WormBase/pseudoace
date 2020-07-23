SHELL := /bin/bash

define print-help
        $(if $(need-help),$(warning $1 -- $2))
endef

need-help := $(filter help,$(MAKECMDGOALS))

help: ; @echo $(if $(need-help),,\
	Type \'$(MAKE)$(dash-f) help\' to get help)

deploy-clojars: $(call print-help,deploy-clojars,"Deploy as library to clojars.org")
	@./scripts/deploy.sh

run-all-tests: $(call print-help,run-all-tests,"Run all test against each flavour of datomic and clojure.")
	@./scripts/run-tests.sh

setup-mvn-creds: $(call print-help,setup-mvn-creds,"Setup credentials for maven/clojars")
	@./scripts/dev-setup.sh

uberjar: $(call print-help,uberjar,"Create uberjar for use in DB migration pipeline")
	@./scripts/bundle-release.sh

.PHONY: uberjar-for-db-mig deploy-clojars setup-mvn-creds
