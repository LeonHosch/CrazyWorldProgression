# Achievement titles

Title definitions live inside custom Minecraft advancement JSON. No separate title definition or admin command is needed. Add this optional field at the top level of an advancement:

```json
"cwp_title": {
  "id": "mymod:crowned",
  "name": "The Crowned",
  "color": "#FFD700"
}
```

The advancement owns the title ID, visible name, and six-digit RGB color. Completing that advancement unlocks the title, including when a MobForge `achievement(achievement_id="...", player="...")` script step awards it. Titles with duplicate IDs or invalid metadata are skipped and logged. Removing the field removes the title on the next data reload.

Players use `/titles` to list unlocked titles, `/titles equip <title_id>` to select one, and `/titles clear` to unequip it. Only the selected title ID is saved in the world. If its achievement is later revoked or the title field is removed, it stops displaying. Metadata changes are picked up on data pack reload.

The selected title appears below the player's overhead name. Its color applies to that name and the tab-list name. Each client independently toggles title text and overhead player names in the settings button of the pause menu (**ESC → CWP Settings**, or the name a content mod gives it).
