TODO:

- [ ] Provide native builds for platforms other than Linux on AMD64.
- [ ] Improve error messages by catching more specific exception types at
      top-level.
- [ ] Replace explicit build ordering with resolved image dependency graphs.
- [ ] Compare Dockerfiles with replacements lazily, line-by-line.
- [ ] Invoke builds concurrently when they have no codependency.
- [ ] Centralise image and tool versions, and their hashes, into one
      place. Automate bumps of all versions in one go, and propagate down to
      images via Docker build arguments.
- [ ] Make file updates concurrent.
- [ ] Cache read files. (`memoize` is too naive; cache eviction may be
      necessary in cases where a multi-stage image needs multiple sweeps
      to update multiple parent image references. Consider
      `clojure/core.cache`.)
