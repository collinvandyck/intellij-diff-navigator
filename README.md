# intellij-diff-navigator

A JetBrains plugin that lists every hunk of your branch's PR in the Find tool window. Step through them with Next/Previous Occurrence, the same keys you use for search results.

The IDE's change markers only show uncommitted work, so once a branch is committed there's nothing in the gutter to jump between. This plugin diffs the branch against its merge base instead, without touching your working tree or staging area.

## Install

Requires GoLand 2026.2 or another JetBrains IDE on build 262 or later, plus [mise](https://mise.jdx.dev) and [just](https://just.systems).

```sh
just install
```

This builds the plugin and asks which installed JetBrains IDEs to add it to, using gum, fzf, or a plain menu, whichever it finds first. Restart the IDE to load it. `just uninstall` removes it.

## Use

Run **Show PR Changes** from Find Action (⌘⇧A). To give it a shortcut, press ⌥↩ on it in Find Action, or search for it under Settings → Keymap.

The Find tool window opens a tab titled `PR changes vs origin/main (abc1234)`, with one entry per hunk, grouped by file. Step through them with Next/Previous Occurrence (⌥⌘↓ / ⌥⌘↑ on macOS). Run the action again to refresh. It replaces its own tab and leaves your other Find tabs alone.

With IdeaVim:

```vim
nmap <leader>gp <Action>(PrChanges.Show)
nmap <C-j> <Action>(NextOccurence)
nmap <C-k> <Action>(PreviousOccurence)
```

## How it picks the base

The diff runs from the merge base of `HEAD` and `origin/main` to your working tree. When there is no `origin/main`, it uses `origin/master`. To use another branch for one repository:

```sh
git config prchanges.base origin/develop
```

Projects with several git roots get one combined list.

## Limits

- Each stop shows the new code. Removed lines aren't shown, so use the diff viewer when you need them.
- Uncommitted edits are included. Untracked files are not.
- The list doesn't refresh on its own. Run the action again after you pull or edit.

## Development

```sh
just test     # unit tests
just build    # plugin zip in build/distributions
just verify   # JetBrains plugin verifier against the GoLand in /Applications
```

The build compiles against the GoLand installed at `/Applications/GoLand.app`, and mise pins JDK 25.
