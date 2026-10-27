# A retry wait of 0 sends again at once

> Decided by the agent releasing the composer's switch changes, 2026-10-27: the composer's end-to-end test's store, `:retries {:max-retries 0 :first-wait-ms 0 :max-wait-ms 0}`, which the settings' shape takes, failed `make-file-storage` with the SDK's "baseDelay must be positive". Since v0.2.3. Changes in part [The bucket through the AWS SDK for Java v2](2026-10-27-the-bucket-through-the-aws-sdk-for-java-v2.md): how `:retries` becomes the SDK's retry strategy.

**Decision:** `:first-wait-ms` or `:max-wait-ms` of 0 is no wait: the retry strategy's backoff is the SDK's `BackoffStrategy/retryImmediately`; both above 0, its `exponentialDelayWithoutJitter`, as before. The shape is unchanged: 0 stays a value it takes, and now one that starts.

**Why:** the SDK's exponential backoff refuses a wait of 0 (base and max alike), so a store the settings' shape let through failed at its start, with the SDK's message, not the settings'. "Never longer than 0 ms" is "at once", which `retryImmediately` is.

**The price:** none beside the branch.

**Instead of:**
- **`:first-wait-ms` and `:max-wait-ms` at least 1:** a store that never retries (`:max-retries 0`) would write a wait it never takes
