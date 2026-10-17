# not-my-todo-store

A TODO store for every repository on this machine, kept outside the repositories, and an MCP server
that gives any Claude Code session the `todo_*` tools, whether or not an IDE is running. The Not My
TODO plugin for JetBrains IDEs is one client of the same store.

## Install

Needs Java 21 or newer and Claude Code.

```
./gradlew installServer
```

This copies the server to `~/.local/share/not-my-todo-store/app` and runs
`claude mcp add notmytodo-store --scope user -e JAVA_HOME=<java> -- <app>/bin/notmytodo-store`.
`<java>` is the Java running Gradle when it is 21 or newer, else the Java 21 the build compiled the
server with, so the server does not depend on the Java of the shell a session was started from.
Started with an older Java, the server says it needs Java 21 and exits. Run `installServer` again
after pulling changes, or to change the Java.

## Where items are kept

`~/.local/share/not-my-todo-store` on Linux (or `$XDG_DATA_HOME/not-my-todo-store`),
`~/Library/Application Support/not-my-todo-store` on macOS, `%LOCALAPPDATA%\not-my-todo-store` on
Windows; `NOT_MY_TODO_STORE` names another folder.

```
store.lock
projects/<key>/project.json     remotes, checkouts on this machine, next id
projects/<key>/items/T-12.json  one item
```

A repository's project is found by its folder or, for another clone or a worktree, by its remotes,
and is made by the first item created in it. Items of no repository go to `inbox`. Ids are `T-1`,
`T-2`, ... within a project; `key/T-12` names an item of another project.

## Tools

The plugin's eight tools (`todo_list`, `todo_get`, `todo_create`, `todo_update`, `todo_comment`,
`todo_decided`, `todo_batch`, `todo_delete`), each with a `project` parameter, plus:

| Tool | Purpose |
|---|---|
| `todo_projects` | The projects, their remotes and checkouts, and how many items are open, wait for a decision, and are fixed |
| `todo_move` | Move an item to another project, for example out of `inbox`; links to it are rewritten |

`todo_list` in a repository with no project yet lists nothing until the first `todo_create` there;
pass `project: "*"` for every project. In `~` or `/` it lists every project. Item files that cannot
be read are named in a trailing `{"unreadable": [...]}` element of the result.

## Build

```
./gradlew test            # core and server tests (needs git)
./gradlew installServer   # install and register the server
./gradlew :core:publishToMavenLocal
```

## License

Copyright 2026 konove. Licensed under the [Apache License, Version 2.0](LICENSE).
