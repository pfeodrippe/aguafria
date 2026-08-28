.PHONY: package verify central-bundle publish next-patch clean

package:
	clojure -T:build package-next-patch

verify:
	clojure -T:build verify-next-patch

central-bundle:
	clojure -T:build central-bundle-next-patch

publish:
	@set -eu; \
	  test -f .m2/settings.xml || { echo "Missing ignored .m2/settings.xml" >&2; exit 1; }; \
	  version="$$(clojure -T:build next-patch)"; \
	  clojure -T:build prepare-central-publish :version "\"$$version\""; \
	  mvn --batch-mode --settings .m2/settings.xml \
	    --file target/release/maven-publish/pom.xml deploy; \
	  clojure -T:build advance-version :version "\"$$version\""

next-patch:
	clojure -T:build next-patch

clean:
	clojure -T:build clean
