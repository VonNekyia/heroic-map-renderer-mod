# Changelog

What changed in the Heroic Map mod, one section per version, newest first.
The release notes on GitHub and Modrinth come from here
(`.github/notizen.sh`); write them in English.

## 0.2.13

- **Beams above pinned waypoints:** each pinned waypoint in view distance shows a beacon beam in its colour in the world. Switch it off with "World effects" in the settings.

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
