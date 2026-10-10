# Changelog

What changed in the Heroic Map mod, one section per version, newest first.
The release notes on GitHub and Modrinth come from here
(`.github/notizen.sh`); write them in English.

## 0.2.27

- **Layers on the full map: the name is a heading, the button only switches.** Each layer shows its name with an "On"/"Off" button next to it. A click on the button turns the layer on or off and does nothing else; a double click on the name pins everything of the layer to the minimap, another one unpins it. Before, the double click went on the switch and made it flicker.

## 0.2.26

- **The arrow sits centred above your head** on the minimap and the full map; before, it stood about half a head pixel to the right.
- **Choose how you show:** under "Settings …" a switch "Player" picks head with arrow, the arrow alone, or the head half see-through with arrow.

## 0.2.25

- **Banners of secret layers:** layers only some players may see now show their banners too; the mod asks the server for each banner it draws, and only players allowed to see the layer get them.

## 0.2.24

- **Banners drawn by the server:** when a layer gives a banner a design, the mod shows the banner the server renders for it, with a crown for capitals; until it arrives, the layer's image stands in.

## 0.2.23

- **Banner names follow an arc:** the name under a banner now curves below it, each letter upright to the arc, like on the web map; pin names stay straight.

## 0.2.22

- **The full map remembers its view per map, not per dimension.** Switching to another map of the same dimension, such as your own, no longer opens it at the zoom of the last one, where your own map may still be empty. After the update the full map starts once at your position.

## 0.2.21

- **Choose the scale of your own map:** next to "Myself" a switch picks 1, 2 or 4 pixels per block, like the server's maps. It stays until you delete the map in the map list.
- **Your own map in singleplayer:** "Myself" now works in singleplayer worlds too. Waypoints and the full map's position are kept there across restarts as well.

## 0.2.20

- **Chests, doors, signs and heads show on the minimap right away,** also with Sodium; before, they only appeared after the next block was placed.

## 0.2.19

- **Compass marks instead of corner ornaments:** the frame now shows N, E, S, W marks on its bands, fixed when the minimap points north and turning with the map when it rotates; the corner ornaments are gone. The Ornaments switch in Settings turns the marks off. With a frame the minimap keeps a little more distance from the screen edge, so turned marks stay on screen.
- **Minimap size follows the window:** the minimap keeps its share of the window's shorter side, so a smaller window gives a smaller minimap. At most 256 GUI units, as before.

## 0.2.18

- **Adding waypoints to a shape works on servers with layers:** after "Add point", the info panel of a region under the waypoints no longer opens where the menu was and swallows the next clicks; no panels open while a shape is being built.
- **Double-click your own region inside a region from the server** pins your region, not the one from the server.

## 0.2.17

- **Layers on the full map:** a "Layers" button opens a list with a switch per layer from the server; the list stays open or closed as you left it.
- **Double-click a layer** to pin everything in it to the minimap, or unpin it all; a colourful dot marks fully pinned layers. The limits of 64 still apply, and the map says what did not fit.

## 0.2.16

- **Your own lines and regions from waypoints,** instead of "Region from here": right-click a waypoint, "Add point", then left-click more waypoints. Click the first one again to close a region, or choose "Finish shape" for a line. Moving a waypoint moves its shapes; deleting it takes it out of them.
- **Pin your own shapes** with a double-click, lines too; "Delete shape" in the right-click menu removes one and keeps the waypoints.
- **Old rectangles stay:** regions set with "Region from here" are still drawn and can be pinned and deleted.

## 0.2.15

- **The full map remembers where it was:** zoom and position per server, world and dimension.
- **"To player" button** on the full map, and **"Options …"** at the bottom right for the menu of /hmap.
- **Double-click a waypoint without the map jumping;** a single click still centres it a moment later.
- **No more pinned info panels:** a click no longer keeps a panel open; panels show while you point at something.
- **Move a waypoint:** hold the left button on it for two seconds, then drag; Esc cancels.
- **New icon:** the mod's logo, a spruce on an island.

## 0.2.14

- **Doors, fences and torches on the minimap:** thin blocks now take at least one pixel, so doors, fence posts, panes, bars and torches show at low resolution too.
- **Chests look like chests** on the minimap and the self-drawn map: the top of their lid instead of plain planks; ender chests no longer black.

## 0.2.13

- **Pins and banners on the minimap only when pinned:** double-click a pin or banner on the full map to pin it; pinned ones show a small dot under them there. Up to 64 per world.
- **Smaller banners,** drawn on whole screen pixels.
- **Banners fade near you:** a pinned banner on the minimap turns see-through as you come closer than 24 blocks, down to 35 % at 8.
- **Regions with a left-click:** after "Region from here", a left-click on the full map sets the second corner; dragging still moves the map.

## 0.2.12

- **Pin regions and circles:** double-click a region or circle from the server, or the diamond of your own region, on the full map; it then shows on the minimap too. Up to 64 per world.

## 0.2.11

- **Your own regions:** right-click on the full map, "Region from here", then "Region to here" marks a rectangle in a waypoint colour, saved per world like waypoints; right-click inside it to delete it.

## 0.2.10

- **Banners and their names** no longer stay away when an image did not load at first: the mod asks again after one minute, after five, then every fifteen minutes.
- **Names under pins and banners** look like the map labels now: dark letters with a light outline, no box.
- **Minimap stays clear:** regions, circles and lines of server layers show on the full map only; pins, banners and labels stay on the minimap.
- **Info panels open faster,** 50 ms after the pointer rests instead of 150; a title colour too dark for the panel is lightened in its own hue until it reads.

## 0.2.9

- **Server layers on the map:** regions, circles, lines and curved map labels from the server plugin, on the minimap and the full map.
- **Pins and banners** in a fixed size on every zoom level, their names in the map font.
- **Info panels:** rest the pointer on a pin, banner or area on the full map to see its panel; a click keeps it open.
- **Biome frame:** a minimap frame that follows the biome you stand in, and your coordinates under the minimap.
- **Rotating minimap** is now the default.
