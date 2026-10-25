# Changelog

What changed in the Heroic Map mod, one section per version, newest first.
The release notes on GitHub and Modrinth come from here
(`.github/notizen.sh`); write them in English.

## 0.2.75

- **Choose the relief of the map: Flat, Relief or Relief + Shadows.** The server draws its map with relief; the mod now computes the other two kinds from a layer the server sends with it. A button on the full-screen map switches between them, the default stays Relief. Flat and Relief + Shadows need a server with renderer 0.19.0 and plugin 0.21.0.
- **The minimap now shows relief up close, and so does your own map.** Until now only the zoomed-out minimap had it; up close it now looks the same, for everyone. Pick Flat to keep the old look.

## 0.2.74

- **Tall crops from resource packs fill their block on the map again.** Some packs build crops taller than a block and leave the top of the texture empty; tilted, the empty part used to squeeze the visible plant into half the block. Plants from the game look exactly as before.

## 0.2.73

- **Plants lean to the north on the minimap and your own map.** Crops, grass, flowers, kelp and seagrass now tilt 45° so you see them from the side instead of a flat patch of texture. Each plant stays inside its block, plants in water stay under the surface, and bamboo leaves and pumpkin and melon stems show too.

## 0.2.72

- **Draw it yourself in new worlds and rounds.** Once you switch on "Draw it yourself" on a server, the mod also draws new worlds and rounds of that server, but never over a map the server offers. Maps it started that way for rounds and one-day worlds go away 7 days after your last visit, and all of them together stay under 500 MB; maps you started yourself always stay.

## 0.2.71

- **"Automatic" for the biome frame.** The frame chooser now shows "Automatic" first for the biome frame, and it's the default: the frame follows the biome you stand in. Pick a biome there instead to keep that frame everywhere.
- **The flower stands on the frame.** With the "biome" frame, the marks east, south and west now stand outside on the edge of the minimap, foot on the frame and bloom pointing out; the letters of "lettern" turn their head outward too. The minimap keeps a little more distance to the screen edge for that.
- **The same world under another address.** If you join under a new address or port and you already have a map of that world under the old one, the map list suggests "Use the map of …" for it. Nothing changes until you click it.
- **A reset world gets a fresh map.** When a server resets a world, the mod notices it and deletes the old map, so nothing is drawn over the wrong terrain; your waypoints stay. It's sure with plugin 0.20.1, which tells the mod each world's ID, or when the same server shows the same world with a new seed. Without that, if the world's time jumps far back, the old map only rests and you can delete it in the map list.
- **Copy another server's map and draw over it.** In the map list, next to "Use the map of …", "Copy" makes the other server's map of the same world your own map on this server, and "Draw it yourself" then draws what changes here. The copy costs hardly any space: until a tile is drawn anew, both maps share its file.

## 0.2.70

- **Plants from resource packs lie flat on the minimap.** Crops and grass from packs that build their own plant models, with tilted faces or their own templates, no longer turn into stripes or vanish on the minimap and your own map; they lie flat like the game's plants.
- **Move a map to another place.** "Move …" in the map list changes the address, server and world hash of a map. "Whole folder", the default, moves the folder of the world with all its maps, waypoints and map view, for example to say which of two folders of the same world belongs to which server; your own map then belongs to the new server. "Only this map" moves just this map, for example into the folder of the new hash after a world reset. Nothing is ever merged: if something is already there, "Move" stays grey and says why. A move happens completely or not at all.
- **No more duplicate maps after changing the storage.** Switching between "IP + Hash" and "IP:Port + Hash" now moves the map folder instead of downloading everything again, and the default port 25565 no longer counts as a difference. The map list marks a map that lies twice as "duplicate"; hover it to see both places and when each was last written, then delete the one you don't need.
- **A server without a name behind the same address no longer makes the mod forget the other server's name.**
- **Move maps between singleplayer and a server.** "Move …" now also takes a world folder or a map from singleplayer to a server and back: choose "Server" or "Singleplayer" and type the name of the save. A world with the same seed has the same hash in both places, so your map and waypoints fit right away.
- **A server address with a dot at the end** now uses the same map folder as without it, on every system.
- **Long paths in the map list are shortened in the middle.** The address stays in front, the world folder and the map name at the end. Hover a shortened path to see it in full.
- **"Server without a name" explains itself.** Hover it in "Maps of this server" to see what it means and what helps.
- **Layers can hide when you zoom out.** A server can now give a layer a zoom limit (plugin 0.20.0); beyond it the layer's pins, banners, areas and map text disappear from the full map and the minimap, and its name in the layer list turns grey. Pinned objects stay.

## 0.2.69

- **Two servers, one world: each keeps its own map.** What the full map shows for a dimension now counts per server, and your own map ("Myself") belongs to the server you drew it on. Drawing your own map on one server no longer hides the map you downloaded on another server behind the same proxy. Choosing "Myself" while a downloaded map is shown asks whether to delete the old map, keep it resting, or cancel; the map list switches each map on or to resting for the server you are on. A resting map gets no sync. The servers need plugin 0.17.2, which tells the mod their names.
- **Use the map of another server.** If two servers behind one address have the same world, the map list offers, at the bottom and only then, "Use the map of …" with the other server's name: this server then shows that server's downloaded map without copying it, read only; your own map is never drawn into it. "unlink" undoes it. "Maps of this server" now says whether the server was recognized. When a server stops showing another server's map, the mod sets this link once by itself, so nobody loses the map.
- **After the update:** if you drew your own map on a second server behind the same proxy, the first one shows its downloaded map again right away. On the second server you see the download until you switch your old own map back on: map list, "take over".
- **Name your waypoints.** Right-click a waypoint on the full map and choose "Name…", like for regions, with size and font below. The name sits under the waypoint in its colour, also on the minimap when it is pinned.
- **Manage all your waypoints, regions and lines in one list.** "Manage +" on the full map, or a key you can set under "Heroic Map" in the controls, opens a panel beside the buttons: one row per object with its colour, name, text size, bend, font, layer, pin, jump-to and delete, plus search, filters and sorting. Every change shows on the map right away and is saved at once; delete needs a second click. Waypoints can now change their colour there.
- **Names shrink far out, like on the web map.** When you zoom far out, the names of waypoints, pins, heads and banners get smaller and fade away; the symbols keep their size. Names of regions and lines are unchanged.
- **Thin chunk lines on the block edges.** Chunk lines now sit exactly on the border between two chunks, where `Ctrl` snaps, instead of on the first block of a chunk, and they are one screen pixel thin at every GUI scale, on the minimap and the full map. The line that lights up while you snap is just as thin.
- **The marks of the biome frame sit on the frame.** The east, south and west marks of the "biome" frame no longer stick out past its narrow band: they rest on it, flush with the outer edge, shadow included, square and round, upright and rotating. North and the other frames stay as they were.
- **Death points and the veil, right in the settings.** The settings submenu now shows a death point with its time and a pinned region with its veil, each with its slider next to it; the veil in the picture grows with its slider. The world effects switch is a symbol next to the region. The separate slider rows are gone.
- **Choose the time under death points.** Click the death point in the settings to switch between date and time, date, time or none. Date and time now always shows both, also on the day you died.
- **Teleporting from the map uses the plain `tp` command.** The server no longer needs to allow `execute`, only `tp`. Without a known height you first go up into the air and land on the ground as soon as your game has the destination; this costs no hearts any more. If the server doesn't send the destination within 5 seconds, or there's no place to stand, you go back where you were. If the server doesn't allow you `/tp`, the map says so and sends nothing.
- **A progress bar under the minimap.** While the map downloads or syncs, a thin bar with the percentage sits under the minimap, so you see it without opening the full map. The coordinates move one line down meanwhile. If a sync finds nothing missing, there's no bar.
- **Heads on map pins.** If a server's layer gives a pin a head, for example an NPC, the map shows that face instead of the pin, with the name below, on the full map and pinned on the minimap. Until the head has loaded, you see the pin.
- **The coordinates are the first minimap widget.** Your choice of coordinates carries over and is now saved as `widgets`.
- **Edit widgets in the minimap menu.** Drag a widget to snap it below or above the minimap, or leave it anywhere on the screen. The mouse wheel makes it bigger or smaller, a click or right-click switches its version, and Del or the × that appears when you hover removes it.
- **Time and biome widgets.** The + under the shape symbol, or a right-click on free space in the minimap menu, adds a widget: coordinates, time or biome. Time shows the game clock in 24 or 12 hours, the day, or your computer's clock; in the Nether and the End it shows the Overworld clock. Biome shows the name of the biome you stand in, optionally with a dot in the colour of the biome frame. Removed coordinates now come back with the + instead of a grey line.
- **Choose your frame from a preview.** Click a mark N, E, S or W on the minimap in the menu to open the frame chooser: a small minimap for each frame, with a colour swatch per variant below it. Click a swatch to recolour the preview, click the preview to use it; Esc or a click beside it keeps your frame. Clicking a mark no longer steps to the next frame.
- **Frames come in families with colours.** "Shield" (grey, gold, paper) and "Rose" (silver, gold, parchment) replace grey, wood, paper, compass, clock and cartographer; your choice moves over by itself. Every biome of the "biome" frame has its own colours too, and you can set each one in the chooser.
- **New frame "Letters"** with upright letters N, E, S, W, inside or outside the minimap, in four colours.
- **Make a mark see-through with the middle mouse button.** The right click now opens the frame chooser.
- **HD marks.** Every mark of a frame can show a finer HD picture: the middle mouse button on a mark in the menu now steps through pixel, HD and see-through. HD looks smooth from GUI scale 2 up; at GUI scale 1 the mark stays in pixels. The handle turns HD as soon as a mark does.
- **Softer frames.** The bands of every frame blend softly into each other, have a fine bevel and cast a light shadow onto the map; round frames get smooth edges. They're drawn in screen pixels, so they look even at every GUI scale.

## 0.2.68

- **The full map zooms toward the mouse.** The spot under the mouse pointer stays under it while you zoom with the mouse wheel, in and out, like on the web map. At the closest and farthest zoom the map doesn't move.

## 0.2.67

- **Syncs catch up more.** A sync may now load up to half of your map instead of a tenth, so after a longer break the map is up to date in one go. How much a sync brings is set by the server's plugin; with an older plugin it stays at a tenth. Without a saved map the mod still asks for a full download.
- **Syncs show their progress.** While a sync runs, the full map shows the same box as the first download next to "Download map …": "Syncing map", how much of what the sync needs has arrived, speed and time left. Until the sync knows what it needs, the box shows "…"; if nothing is missing, there is no box.

## 0.2.66

- **Your own map stays sharp when you zoom out.** Each coarser level of the self-drawn map now keeps one pixel of every 2 × 2, like the maps from the server, instead of averaging them; averaged, it got blurrier with every level. Maps drawn with earlier versions are rebuilt once in the background, while you play in that dimension.
- **The minimap zooms out one step further,** to 0.5×: it shows four times the area, one pixel per block at GUI scale 2.
- **Minimap zoom is a slider** in the menu, standing upright right next to the minimap, with one notch per step; up is closer, and the tooltip names the level. The mouse wheel over the minimap or the slider steps one notch. The zoom key still steps through all levels.
- **The menu buttons move aside:** after you drag the minimap or resize it, the buttons go to the free side next to it.
- **Zoom the minimap much further out,** down to 1/32: the slider now has nine notches from 1/32× to 8×. From 1/4× on, the minimap shows the tiles of your map, like the full map: from a download it then looks like the web map, and nearby changes appear once the tiles have them. Without a map it shows just the loaded chunks in the middle.
- **Minecraft font for names.** Under the size and curve sliders of a shape's name sits a "Font" button: Map or Minecraft. It shows right away on the full map and the minimap; Enter saves, Esc undoes. With "Force Unicode Font" the game draws its Unicode font; capital letters stay the same height in all fonts. Only your own shapes; names from the server stay in the map font.
- **Lines and regions now sit between blocks.** Corners of your own lines and regions lie on the edges between blocks instead of in the middle of a block, so two joined regions share an exact border. Waypoints stay on blocks; a corner at a waypoint sits on the nearest corner of its block. **Existing shapes move half a block north-west:** the saved numbers stay the same and now mean the north-west corner of their block, so nothing needs converting and shapes that touched still touch. Older versions show the same shapes half a block south-east.
- **Resolution is a slider under the minimap** in the menu, one notch per step from 1 to 16 px; the mouse wheel over it steps one notch.
- **Click the coordinates** under the minimap in the menu to switch their style: x z, x y z, off. When off, the menu shows a grey "Coordinates off" line so you can click it back on; in the game it stays hidden. Both settings left the settings submenu.
- **Settings right at the minimap.** In the menu, the controls around the minimap form an L: the zoom slider with a north arrow on top, the resolution slider with a grid symbol at its end, and a ◻/◯ symbol in the corner between them. Click the symbol to switch square or round, the north arrow to switch rotating, the grid for chunk lines. Right-click a mark N, E, S or W on the frame to make just that mark see-through; right-click it again to bring it back. The settings submenu is grouped into Waypoints, In the world and a collapsible Technical group.
- **Ctrl snaps to chunk lines.** While you drag a corner or waypoint with `Ctrl`, it snaps onto a chunk line within reach, x or z, both near a chunk corner; the line lights up while you drag. A corner or waypoint within reach still wins. Only when the full map shows chunk lines. With `Alt` it only snaps, there is nothing to connect.
- At GUI scale 4 with a large minimap, the menu buttons lie over the zoom slider and take the click, as they did over the minimap before.
- **One map folder per server behind a proxy.** With the plugin from 0.14.0 on, the server names its map, and the mod keeps each server's map, waypoints and layers in its own folder, even when two servers share a world. A server that shows another server's map uses that server's folder. Existing folders move once on the first join. From the second join on, the mod remembers the map and uses the right folder right away.

## 0.2.64

- **Alt connects while you drag.** Hold `Alt` (`⌥` on a Mac) while dragging a corner or waypoint: it snaps like with `Ctrl` and connects on release; both corners already show the light centre while you drag. If the target can't be connected, it only snaps and says why. `Ctrl` alone still only snaps, on a Mac too (`Ctrl`, not `⌘`).
- A corner can now be connected even if an unrelated waypoint sits on the target's block.

## 0.2.63

- **Reorder the layers.** In the layer list of the full map, ▲ and ▼ move a layer up or down; the upper one is drawn on top, on the full map and the minimap. Your order is kept per server world; "Server order" puts it back. Until you reorder, nothing changes.

## 0.2.62

- **Time of death.** On the full map, each death point shows when you died: the time for today, otherwise date and time. Death points from older versions have no time.

## 0.2.61

- **Connect points.** Right-click a corner of your own line or region, or a waypoint, and choose "Connect": it snaps onto the nearest point within a short distance, and from then on they move together. Drag one, move it with "Set coordinates…" or move a shape by its handle, and the connected corners and waypoint follow; only the shared corner of another shape moves, the rest of that shape stays. "Disconnect" releases all points on that spot. Connected corners have a light centre. Shared layer codes don't carry connections.

## 0.2.60

- **Which shape lies on top.** Under "Color…" on a shape's handle sits a weight slider from −9 to +9. A heavier shape is drawn above lighter ones, fill, border and name, on the full map and the minimap; clicks hit the shape on top. Where two pinned regions share an edge, only the heavier one's veil shows.

## 0.2.59

- **Bigger, smaller and curved names.** When you name a shape, two sliders sit under the field: text size from ¼× to 4×, and for regions a curve of up to 180° upward or downward. The map shows them right away; Enter keeps them, Esc undoes them. Shared codes carry them along.

## 0.2.58

- **Several death points.** "Death points" under "Settings …" is now a slider: off, or keep 1 to 16. Each death adds one where you died; the oldest drop off. Lowering the slider removes the oldest when you close the menu, not while you drag. Only the newest is pinned, so the beam leads to your last death. Death points can't be dragged any more; delete them from the right-click menu or with `Delete`.

## 0.2.57

- **New version hint.** When Modrinth has a newer Heroic Map for your game version, the full map shows it top left. A click opens the Modrinth page after the game's usual link prompt. Turn it off with "Report new version" under "Settings …"; then the mod doesn't ask Modrinth at all.
- **Snap with Ctrl.** Hold `Ctrl` (`⌘` on a Mac) while you drag a corner or a waypoint on the full map, and it snaps onto a nearby corner of another shape or, for a corner, onto a waypoint. Regions now meet cleanly.

## 0.2.56

- **Coordinates stay on screen.** The line under the minimap no longer runs off the edge with large numbers and the height shown: it moves toward the screen and ends flush with the minimap in a corner. If there is room neither below nor above the minimap, it sits at the bottom of the screen.

## 0.2.55

- **A menu for what you click.** Right-click a waypoint, a corner or the diamond of an old rectangle on the full map, and the menu shows only what concerns it. The free map keeps the general menu.
- **Set coordinates.** "Set coordinates…" on a waypoint or a corner moves it to the `x z` you type.
- **Delete with a key.** `Delete` or `Backspace` removes the waypoint, corner, shape (at its handle) or old rectangle under the mouse.
- **Right-click finishes a shape.** While you build a line or region on the full map, a right-click finishes it: two corners make a line, more make a region. With only one corner, a note says so. Right-click a shape's handle to get its menu, including "Cancel shape"; `Esc` cancels as before.

## 0.2.54

- **Open the web map in your browser, already logged in.** "Open in browser" on the full map, or `/hmap web`, opens the server's web map and logs you in, so it knows exactly who you are and lets you click there too. It needs a server with a matching Heroic Map plugin; otherwise the button is grey and says why.

## 0.2.53

- **Buttons in info panels.** A server plugin can put buttons under the info panel of a place on the full map, for example "Ship" at every town. Click one to switch it on or off for you; the square on the left is filled while it is on. If the server does not answer within a few seconds, the button is free again and a note says so.

## 0.2.52

- **Share a layer as a code.** In "Layers …", the copy button next to a layer puts it on the clipboard as a short text; anyone can paste it with "Paste code" and gets it as a new layer of their own. Pinned things, players and the death point stay at home.

## 0.2.51

- **Your own layers.** Waypoints, lines and regions can live in layers of your own and be switched off together. Right-click one and choose "Layer …" to move it, or to create a new layer on the spot. Your layers sit in the layer list on the full map, before the ones from the server, and in "Layers …" in the settings, where you can rename and delete them. Everything starts in "Base".

## 0.2.50

- **Grass, flowers, crops and seagrass on the minimap.** Seen from above, plants used to stand on their edge and vanish. The minimap now lays each one flat over its block, tinted like the plant, the same way the map tiles from the server do. Your own map drawn with "Self" shows them too, once a chunk is drawn again.

## 0.2.49

- **Teleport from the full map always works.** "Teleport here" is in the map's menu in every dimension, also in the Nether. Without a height from your client or the map, the server looks for the ground first; where it has not loaded the chunk, you land at your own height and the mod moves you onto the ground as soon as the chunk arrives. If you land inside blocks or high in the air, it corrects that too. Landing where the server must first generate the chunk can cost half a heart to a few hearts.

## 0.2.48

- **A death point where you died.** When you die, the mod sets a pinned waypoint on that spot, drawn dark with a light cross, so the minimap and the beam lead you back. Only one: the next death moves it. The chat says where; click the message to open the map there. "Death point" in "Settings …" turns it off.
- **A waypoint at typed coordinates.** Right-click the full map and choose "Waypoint at …": type `x z` (or `x y z`; y does not count) and press Enter.

## 0.2.47

- **Notes at the bottom left go away.** A note on the full map, such as a shape that could not be saved, now disappears after five seconds or at your next click or key press, instead of staying until you close the map.

## 0.2.46

- **Pick any color for your lines and regions.** Right-click a shape's handle and choose "Color…": type a color like `#3A7FD5` and press Enter. It shows everywhere the shape does, on both maps and in the veil. Leave it empty to go back to the color from the list.
- **A taller veil for your own regions.** "Veil" next to "World effects" in "Settings …" raises the veil at the edge of your pinned regions from 4 up to 16 blocks, and makes it more opaque as it grows. Regions from the server stay as they are.

## 0.2.45

- **The full map stays sharp while changing zoom levels.** Until the tiles of the new level are loaded, it shows the finer level you just saw, scaled down, instead of a dark screen or the coarser level scaled up.

## 0.2.44

- **Name your lines and regions.** Right-click a shape's handle and choose "Name…": type a name and press Enter. It shows on the full map in the same lettering as the names from the server, sized to the shape and next to the handle, and on the minimap when the shape is pinned. Leave it empty to remove the name; Esc cancels.

## 0.2.43

- **"On F3" works with BetterF3.** BetterF3 keeps the debug screen switched on while it slides out, so the minimap stayed centered or hidden until the animation ended; now it returns as soon as you press F3.

## 0.2.42

- **Lines and regions follow while you drag a corner.** Hold a corner for half a second and move it: the shape moves with it right away, instead of jumping into place when you let go.
- **Move a whole line or region by its handle.** Every shape has a handle in its middle, halfway along a line or at the centre of a region; hold it for half a second and drag to move the whole shape. Esc puts it back.
- **What you can click lights up.** Waypoints, corners and handles get a bright outline while the mouse is over them on the full map.
- **Right-click the handle for the shape's menu.** Pin or unpin, add corners, delete. A right-click elsewhere inside a region no longer offers to delete it; old rectangles are deleted from their diamond.

## 0.2.41

- **The frame ornaments face outward.** With the "Compass" and "Biome" frames, every marker now points away from the middle of the minimap instead of all pointing north, so looking south no longer turns them all upside down.
- **The minimap stands still in the menu.** With "Rotate" on, it stays north-up while the menu is open, so the markers no longer wander over the resize handle.
- **"With F3"** in "Settings …": while the debug screen is open, the minimap can stay, hide, or move to the middle of the screen at the same height.

## 0.2.40

- **The first download of a map is one stream and takes minutes, not twenty.** With a map server that offers it, the mod fetches the whole map over a single connection; later updates still come tile by tile. Older map servers keep the old way.
- **The full map shows the download's progress** in a box next to "Download map …": percent, bytes, speed and time left. The map appears as soon as its coarsest zoom level is in, before the download is complete.
- **An interrupted first download continues where it stopped,** right away and on its own, instead of starting over.
- **A stopped download names the HTTP status,** such as 502, which tells a proxy error from a network error.

## 0.2.39

- **"Finish shape" says so when it cannot save.** If the shape you were adding corners to was deleted in the meantime, the new corners were dropped silently; now a note at the bottom left tells you.
- **The info panel on the full map keeps a sensible size at large GUI scales.** It now has its own whole-number scale, at most about a fifth of the window wide; at 1920 × 1080 it looks the same with GUI scale Auto (4) as with 2, and it never gets smaller than at GUI scale 2 unless you chose 1.

## 0.2.38

- **Lines and regions have their own corners.** Right-click, "Line/region from here", then left-click anywhere on the full map to set the next corner, on a waypoint or on empty ground; click the first corner to close a region. Corners show as small diamonds: right-click one to add corners after it or to delete it, hold it for 0.5 s to move it. No waypoints are created for corners.
- **Existing lines and regions keep their shape:** their waypoints become corners once. Waypoints and shapes are now separate, so moving or deleting a waypoint no longer changes a shape. An older version of the mod cannot read the new shapes.

## 0.2.37

- **Holding a waypoint to move it takes 0.5 s** instead of 2 s.
- **A quick click on a second waypoint is no longer a double click on the first.** Clicking B right after A pinned A instead of centering B; a double click now only counts near the first click.

## 0.2.36

- **"Teleport here" works in places no one has loaded yet.** The mod now picks the height itself: from the chunk if your game has it, otherwise from the ground of the server's map, one region fetched on demand. Before, the server looked for the ground itself and refused where its chunks were not loaded. Without a known height, the old way stays, with a note in the chat.

## 0.2.35

- **Maps are stored under the server's name,** such as play.example.net, when the server reports one, whatever address you typed; existing maps move there once, without a new download.

## 0.2.34

- **Clicks and holds on the full map work with a shaky hand.** Adding a waypoint to a shape with a left click and holding a waypoint for 2 s to move it failed when the mouse trembled by a pixel or two: the map added up every tiny movement and took it for dragging. Now only the distance from where you pressed counts.

## 0.2.33

- **Map downloads use 8 connections instead of 4,** so they go about twice as fast for players far from the server.

## 0.2.32

- **Veils build faster** around many pinned regions: the mod picks the nearest pieces without sorting all of them and only splits edges within reach.

## 0.2.31

- **Veils along pinned regions:** the edge of each pinned region and circle shows a soft veil in its colour, standing on the ground and fading out over four blocks. "World effects" in the settings switches it off together with the beams.

## 0.2.30

- **Beams above pinned waypoints:** each pinned waypoint in view distance shows a beacon beam in its colour in the world. Switch it off with "World effects" in the settings.

## 0.2.29

- **An API for other client mods:** a Fabric mod can add its own layers with pins, map text, areas, circles and lines, in the same JSON as server layers; they show on the minimap and the full map with their own switch. See docs/api.md.

## 0.2.28

- **Change the look right on the minimap.** In the `/hmap` menu and under "Settings …", click yourself on the minimap to switch between head, arrow and half see-through, and click one of the marks N, E, S, W to switch the frame. The buttons "Player" and "Frame" are gone. With ornaments off or without a frame, the menu shows the marks half see-through so you can still click them. Dragging still moves the minimap.

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
