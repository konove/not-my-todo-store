package io.github.konove.notmytodo.server

/** The tools as MCP lists them. The descriptions are the plugin's, which agents already use well. */
object TodoToolSpecs {
    const val PROJECT =
        "Project to work in: its key (see todo_projects), inbox, or a folder. Leave it out for the project of the folder Claude Code runs in. " +
            "An id written key/T-12 names its own project and needs no project"

    private const val FULL =
        "true to get the whole item back as JSON, as todo_get with full=true gives it but without the code. Leave it out: you know the item, and the one line shows that the change landed"

    fun all(tools: TodoTools): List<Tool> = listOf(
        Tool(
            "todo_list",
            "List the TODO items kept by not-my-todo-store for the project, in id order. " +
                "All filters must match. Returns a JSON array. Anchored items include, for each of their anchors, the file path and " +
                "current line range but not the code; use todo_get for the code. To choose what to work on next, take the highest priority first and, " +
                "within a priority, the least effort. An item with open parts has the highest priority among them, here and wherever it is shown, whatever was set on it. Pass lost=true after moving or rewriting code " +
                "to find the items that need re-attaching: the files are read from disk again for every answer, so lost is as the code is now; a fixed, done or wont_fix item is never lost, as its anchors stay where they were when it was closed. Pass blocked=false to find the items that can be worked on now. Pass compact=true to survey many items, " +
                "for example to check for a duplicate before todo_create; with anyText one call looks for it under every name it may have. " +
                "In a compact line, an item that has parts says how many of them are open, fixed and closed as parts, for example \"2 open, 2 fixed, 1 closed\", " +
                "leaving out a group that counts none: at \"3 fixed, 2 closed\", with none open, the parent itself can be set to fixed, and at \"5 closed\" " +
                "it is time to close the parent yourself, which is never closed for you.",
            listOf(
                Param("status", ParamType.STRING, "Only items with one of these statuses, separated by commas or spaces: open, in_progress, fixed, done, wont_fix"),
                Param("tags", ParamType.STRING, "Only items that have all of these tags, separated by commas or spaces"),
                Param("priority", ParamType.STRING, "Only items with this priority: p1, p2 or p3"),
                Param("path", ParamType.STRING, "Only items with an anchor in this file or anywhere under this directory, as a path relative to the root of the project's checkout"),
                Param("withoutTags", ParamType.STRING, "Leave out items that have any of these tags, separated by commas or spaces"),
                Param("text", ParamType.STRING, "Only items whose title or details contain every one of these words, in any case"),
                Param("compact", ParamType.BOOLEAN, "One line per item with id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor), and for an item that has parts, parts: how many of them are open (open or in_progress), fixed and closed (done or wont_fix), for example \"2 open, 2 fixed, 1 closed\", without a group that counts none; no details"),
                Param("lost", ParamType.BOOLEAN, "Only items with a lost anchor: the code or file they were attached to can no longer be found. Re-attach them with todo_update. Fixed, done and wont_fix items are never lost"),
                Param("blocked", ParamType.BOOLEAN, "true for only blocked items, false for only items that are not blocked. An item is blocked while one it is blocked by is not done or wont_fix"),
                Param("parent", ParamType.STRING, "Only the parts of this item: the items that have it as their parent"),
                Param("effort", ParamType.STRING, "Only items with this effort: s, m or l"),
                Param("anyText", ParamType.STRING, "Only items whose title or details contain at least one of these words or \"quoted phrases\", in any case, separated by commas or spaces, for example: cache memoise \"lookup table\""),
                Param("project", ParamType.STRING, "$PROJECT. Pass * to list the items of every project"),
            ),
        ) { a ->
            tools.list(
                a.string("status"), a.string("tags"), a.string("priority"), a.string("path"), a.string("withoutTags"), a.string("text"),
                a.bool("compact"), a.bool("lost"), a.boolOrNull("blocked"), a.string("parent"), a.string("effort"), a.string("anyText"),
                a.string("project"),
            )
        },
        Tool(
            "todo_get",
            "Get one TODO item by id (for example T-12) as JSON, with its details, its comments and, for an item anchored to code, " +
                "its anchors, each with the path, the current line range, lost and code, the code at those lines now. An anchor without lines is on the whole file. " +
                "An anchor that is lost has no code but text, the code it was attached to, which is the clue to where it belongs now. " +
                "The anchors of a fixed, done or wont_fix item stay where they were when it was closed and are never lost: they have no code but text, the code they were attached to then. " +
                "Pass full=true for the text and the lines before and after it as they are stored, for every anchor. " +
                "toDecide is what the user has to decide, and decisions what the user was asked and answered. " +
                "Its links to other items are blockedBy, duplicateOf and parent; blocked is true while a blocker is still open, " +
                "and children lists the items that have this one as their parent, with parts saying how many of them are open (open or in_progress), fixed and closed (done or wont_fix), " +
                "for example \"2 open, 2 fixed, 1 closed\", without a group that counts none. A parent is not closed when its parts are: close it yourself.",
            listOf(
                Param("id", ParamType.STRING, "Item id, for example T-12", required = true),
                Param("full", ParamType.BOOLEAN, "true to get, for every anchor, the stored text and the lines before and after it as well. Leave it out: code has the same lines as they are now"),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a -> tools.get(a.required("id"), a.bool("full"), a.string("project")) },
        Tool(
            "todo_create",
            "Create a TODO item for the user to triage. Give path and startLine to attach it to code, or path alone to " +
                "attach it to the whole file; do not make up a line range for something that concerns a file as a whole. " +
                "Give places to attach it to several places. Leave them all out for a plain note. When you add several items that belong " +
                "together, create one item for the whole first and give its id as parent to the others, or file them all in one todo_batch. " +
                "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
                "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it; pass full=true for the whole item as JSON.",
            listOf(
                Param("title", ParamType.STRING, "Short description of what needs doing", required = true),
                Param("details", ParamType.STRING, "Longer explanation"),
                Param("priority", ParamType.STRING, "p1, p2 or p3; defaults to p2"),
                Param("tags", ParamType.STRING, "Tags separated by commas or spaces. When the user has to decide something before this can be fixed, pass toDecide and not the needs-decision tag alone"),
                Param("path", ParamType.STRING, "File to attach to, as a path relative to the root of the project's checkout"),
                Param("startLine", ParamType.INTEGER, "First line of the code, 1-based; leave out to attach to the whole file"),
                Param("endLine", ParamType.INTEGER, "Last line of the code, 1-based and inclusive; defaults to startLine"),
                Param("places", ParamType.STRING, "More places to attach to, separated by commas or line breaks, each written path, path:12 or path:12-20"),
                Param("blockedBy", ParamType.STRING, "Ids of the items that must be closed before this one can be worked on, separated by commas or spaces"),
                Param("duplicateOf", ParamType.STRING, "Id of the item that already says what this one says"),
                Param("parent", ParamType.STRING, "Id of the item this one is a part of. A parent cannot have a parent of its own"),
                Param("source", ParamType.STRING, "Where the item comes from: the commit, the range of commits or the run that left it behind, for example 30c6f6ae..e9ac6245. HEAD, or another name git knows, is stored as the id of the commit it names now, and so is each end of a range such as HEAD~3..HEAD. Put it here and not in details"),
                Param("toDecide", ParamType.STRING, "What the user has to decide before this can be fixed, as Markdown. Passing it tags the item needs-decision, so that no agent fixes it before the user has decided"),
                Param("effort", ParamType.STRING, "Your rough guess at how much work it is: s for under an hour, m for a few hours, l for a day or more. Pass it whenever you can tell"),
                Param("full", ParamType.BOOLEAN, FULL),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a ->
            tools.create(
                a.required("title"), a.string("details"), a.string("priority"), a.string("tags"), a.string("path"),
                a.int("startLine"), a.int("endLine"), a.string("places"), a.string("blockedBy"), a.string("duplicateOf"), a.string("parent"),
                a.string("source"), a.string("toDecide"), a.string("effort"), a.bool("full"), a.string("project"),
            )
        },
        Tool(
            "todo_update",
            "Change fields of a TODO item. Only the fields you pass are changed. Make every change to an item in one call: " +
                "status, resolution, fixedIn and the place it is attached to belong in the same todo_update, not in a call each. " +
                "details is replaced whole: " +
                "to add a finding or a progress note, use todo_comment instead. When you move or rewrite the code " +
                "an item is attached to, pass startLine (and path, if the file changed) to re-attach it; for an item attached to " +
                "several places, path says which one moves. Pass places to set the whole list of places instead. Set status to \"fixed\" when you " +
                "have finished the work so the user can review it, or to \"wont_fix\" to close it without a change; either way pass " +
                "resolution to say what you did or why. A parent whose parts show none open, for example \"3 fixed, 2 closed\", can itself be set to \"fixed\". When you commit the work, name the item id in the commit message, for example " +
                "\"Fix T-12: ...\": that commit is then shown as the one that fixed the item, with nothing to record after it. " +
                "Pass fixedIn only for a commit whose message does not name the item. " +
                "When an item says what another already says, set duplicateOf to that other item and status to \"wont_fix\". " +
                "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
                "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it, and changed, the names of the fields that changed. Of those, fixedIn, source, effort, parent, duplicateOf and blockedBy " +
                "are shown as saved, null when taken away, as status, priority and tags always are: check them there, without a todo_get. Pass full=true for the whole item as JSON.",
            listOf(
                Param("id", ParamType.STRING, "Item id, for example T-12", required = true),
                Param("title", ParamType.STRING, "New title"),
                Param("details", ParamType.STRING, "New details"),
                Param("priority", ParamType.STRING, "p1, p2 or p3. On an item with open parts it is kept but not shown until they are closed: set the priority of the parts instead"),
                Param("tags", ParamType.STRING, "Replacement tags separated by commas or spaces. An item tagged needs-decision waits for the user: do not fix it. To make an item wait, pass toDecide; todo_decided takes the tag away, so do not pass tags to drop it"),
                Param("status", ParamType.STRING, "open, in_progress, fixed, done or wont_fix"),
                Param("path", ParamType.STRING, "File to re-attach to, as a path relative to the root of the project's checkout; defaults to the item's current file"),
                Param("startLine", ParamType.INTEGER, "First line of the code to re-attach to, 1-based; leave out with a path to attach to the whole file. The line it already has re-attaches an anchor that is lost to the code that is there now"),
                Param("endLine", ParamType.INTEGER, "Last line of the code, 1-based and inclusive; defaults to startLine"),
                Param("places", ParamType.STRING, "Every place the item is to be attached to, replacing the ones it has, separated by commas or line breaks, each written path, path:12 or path:12-20. Places it already has are kept as they are, except one whose anchor is lost: that one is attached to the code that is at those lines now. Pass an empty text to make it a plain note"),
                Param("blockedBy", ParamType.STRING, "Ids of the items that must be closed before this one can be worked on, separated by commas or spaces, replacing the ones it has. Pass an empty text to unblock it"),
                Param("duplicateOf", ParamType.STRING, "Id of the item that already says what this one says. Pass an empty text to take the link away"),
                Param("parent", ParamType.STRING, "Id of the item this one is a part of. A parent cannot have a parent of its own. Pass an empty text to take it out of its parent"),
                Param("source", ParamType.STRING, "Where the item comes from: the commit, the range of commits or the run that left it behind. HEAD, or another name git knows, is stored as the id of the commit it names now, and so is each end of a range such as HEAD~3..HEAD. Pass an empty text to take it away"),
                Param("fixedIn", ParamType.STRING, "Id of the commit that fixed the item. Leave it out when the commit message names the item id: the commit is then read from git, and a fixedIn you pass is shown in its place. HEAD, or another name git knows, is stored as the id of the commit it names now. Pass an empty text to take it away"),
                Param("resolution", ParamType.STRING, "What you did to fix the item, or why it is closed without a change, as Markdown in a sentence or two. Pass an empty text to take it away"),
                Param("toDecide", ParamType.STRING, "What the user has to decide before this can be fixed, as Markdown. Passing it tags the item needs-decision, so that no agent fixes it before the user has decided. Pass an empty text to take it away"),
                Param("effort", ParamType.STRING, "A rough guess at how much work it is: s for under an hour, m for a few hours, l for a day or more. Pass an empty text to take it away"),
                Param("full", ParamType.BOOLEAN, FULL),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a ->
            tools.update(
                a.required("id"), a.string("title"), a.string("details"), a.string("priority"), a.string("tags"),
                a.string("status"), a.string("path"), a.int("startLine"), a.int("endLine"), a.string("places"), a.string("blockedBy"),
                a.string("duplicateOf"), a.string("parent"), a.string("source"), a.string("fixedIn"), a.string("resolution"),
                a.string("toDecide"), a.string("effort"), a.bool("full"), a.string("project"),
            )
        },
        Tool(
            "todo_comment",
            "Add a comment to a TODO item: a finding or a progress note. What the user has to decide goes in toDecide " +
                "of todo_update instead. The user is shown " +
                "a comment on an item that is in progress or tagged needs-decision as soon as it is added. It is appended to " +
                "the item's comments with your authorship and the time, and nothing else on the item is changed, so " +
                "prefer it to rewriting details with todo_update. " +
                "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
                "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it; pass full=true for the whole item as JSON.",
            listOf(
                Param("id", ParamType.STRING, "Item id, for example T-12", required = true),
                Param("text", ParamType.STRING, "The comment, as Markdown", required = true),
                Param("full", ParamType.BOOLEAN, FULL),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a -> tools.comment(a.required("id"), a.required("text"), a.bool("full"), a.string("project")) },
        Tool(
            "todo_batch",
            "Create or change several TODO items in one call, saved as one write: use it to file a list of findings or to " +
                "re-tag, re-prioritise or close many items, instead of calling todo_create or todo_update for each or editing " +
                "the items file. items is an array of objects. An object with an id changes that item and takes the " +
                "fields of todo_update, path, startLine, endLine and places to re-attach it among them, and comment as well, which adds a comment " +
                "as todo_comment does, and decisions, which records what the user decided as todo_decided does, before the other fields are applied: " +
                "so after asking the user about several items, one call records every answer. An object without an id is a new item " +
                "and takes the fields of todo_create, and ref as well: a name of your choosing by which the entries after it " +
                "name the new item, written \"\$ref\" (a \$ and the name), in parent, blockedBy and duplicateOf. So one call files a whole item and its parts. " +
                "The entries are applied in order, and when one is wrong none is saved " +
                "and the error names it. Returns one line per entry with the id, title, priority, effort, status and tags of the item as saved, " +
                "which for a new item is how you learn its id, and for an item that has parts with parts, how many of them are open, fixed and closed " +
                "after the whole batch, for example \"3 fixed, 2 closed\".",
            listOf(
                Param(
                    "items",
                    ParamType.OBJECTS,
                    "Array of objects, passed as an array and not as a text, for example [{\"title\": \"Rework the index\", \"ref\": \"whole\"}, " +
                        "{\"title\": \"Cache the index\", \"parent\": \"\$whole\", \"priority\": \"p3\", \"effort\": \"s\", \"tags\": \"perf\", " +
                        "\"path\": \"src/Index.kt\", \"startLine\": 12}, {\"id\": \"T-3\", \"tags\": \"perf mcp\", \"blockedBy\": \"\$whole\"}, " +
                        "{\"id\": \"T-4\", \"status\": \"wont_fix\", \"resolution\": \"...\"}, " +
                        "{\"id\": \"T-5\", \"toDecide\": \"Which of the two?\"}, {\"id\": \"T-6\", \"path\": \"src/Moved.kt\", \"startLine\": 40}, " +
                        "{\"id\": \"T-7\", \"decisions\": [{\"question\": \"Which license?\", \"options\": [\"MIT\", \"Apache 2.0\"], \"answer\": \"MIT\"}]}]",
                    required = true,
                ),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a -> tools.batch(a.entries("items"), a.string("project")) },
        Tool(
            "todo_decided",
            "Record what the user decided about a TODO item: call it when you have asked the user what an item tagged " +
                "needs-decision waits for and the user has answered, before you do the work. The questions, the options and the " +
                "answers are appended to the item's decisions with the time, and the needs-decision tag is taken away: the item no longer " +
                "waits for the user, so there is no need to pass tags to todo_update afterwards. toDecide stays as the record of what was asked. " +
                "When the user answered only some of the questions, pass what is still open as toDecide to todo_update, which makes the item wait again. " +
                "The status is not changed. To record the answers about several items in one call, pass decisions on their entries of todo_batch. " +
                "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
                "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it, and changed, the names of the fields that changed; pass full=true for the whole item as JSON.",
            listOf(
                Param("id", ParamType.STRING, "Item id, for example T-12", required = true),
                Param(
                    "decisions",
                    ParamType.OBJECTS,
                    "Array, passed as an array and not as a text, with one object for each question you asked: question, options (an array of the answers you offered, " +
                        "left out when you offered none) and answer (the option the user chose or what the user typed, in the user's words), " +
                        "for example [{\"question\": \"Which license?\", \"options\": [\"MIT\", \"Apache 2.0\"], \"answer\": \"MIT\"}]",
                    required = true,
                ),
                Param("full", ParamType.BOOLEAN, FULL),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a -> tools.decided(a.required("id"), a.entries("decisions"), a.bool("full"), a.string("project")) },
        Tool(
            "todo_delete",
            "Delete a TODO item that you made: one made to try the tools, or filed by mistake. Do not edit the items file to remove an item. " +
                "Only an item made by an agent can be deleted, and only when it is not the parent of an item and no item names it in " +
                "blockedBy or duplicateOf; anything else is refused with an error that says why. An item the user made, or work that will not be done, " +
                "is closed with todo_update and status \"wont_fix\" instead. Deleting cannot be undone. Returns one line with deleted, the id, and the title of the item that is gone.",
            listOf(
                Param("id", ParamType.STRING, "Item id, for example T-12", required = true),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a -> tools.delete(a.required("id"), a.string("project")) },
        Tool(
            "todo_projects",
            "List the projects of this machine: each repository with items, and inbox, which holds items that belong to no repository. " +
                "Gives each project's key, whether it is the current one, its remotes, its checkouts on this machine, and how many of its " +
                "items are open, wait for the user's decision and are fixed. Use a key as project in the other tools, or in an id written key/T-12.",
            emptyList(),
        ) { tools.projects() },
        Tool(
            "todo_move",
            "Move a TODO item to another project, typically from inbox into the repository it turned out to belong to. It gets the next id " +
                "there and keeps where it came from in movedFrom; every link to it, in any project, is rewritten. Its anchors are dropped, " +
                "since they were relative to the other checkout: attach it again with todo_update. A part cannot be moved on its own, and an item " +
                "with parts moves only with withParts=true. Returns moved, each old qualified id with its new one.",
            listOf(
                Param("id", ParamType.STRING, "Item id, for example inbox/T-4, or T-4 in the project given by project", required = true),
                Param("to", ParamType.STRING, "Project to move it to: its key, inbox, or a folder", required = true),
                Param("withParts", ParamType.BOOLEAN, "true to move an item that has parts together with them"),
                Param("project", ParamType.STRING, PROJECT),
            ),
        ) { a -> tools.move(a.required("id"), a.required("to"), a.bool("withParts"), a.string("project")) },
    )
}
