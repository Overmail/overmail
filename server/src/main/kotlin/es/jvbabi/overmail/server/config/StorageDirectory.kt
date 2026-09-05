package es.jvbabi.overmail.server.config

/**
 * Where the config file and everything the server generates live -- `./data` next to the working
 * directory when it is started from a checkout, and whatever the deployed image mounts, which is
 * why the environment can override it (see deploy/production/entrypoint.bash).
 */
val defaultStorageDirectory: String
    get() = System.getenv("OVERMAIL_STORAGE_DIRECTORY") ?: "./data"
