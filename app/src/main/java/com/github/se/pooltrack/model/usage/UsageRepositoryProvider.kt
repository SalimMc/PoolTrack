package com.github.se.pooltrack.model.usage

/**
 * Provides a single instance of the repository in the app. `repository` is mutable for testing
 * purposes.
 */
object UsageRepositoryProvider {
  var repository: UsageRepository = UsageRepositoryFirestore()
}
