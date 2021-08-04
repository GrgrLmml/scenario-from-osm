# Helper scripts to generate MATSim scenarios from OSM

## 1. Getting the osm
If you want to create a scenario for a smaller area then you can go directly to openstreetmap.org and export from there.
This will give you a file called `map.osm` which is just an XML file. In order to use it you have to convert it to `.pbf`,
which is a protocol buffer data format. You can convert the file e.g. with [osmium](https://osmcode.org/osmium-tool/manual.html).

