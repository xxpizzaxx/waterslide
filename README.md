# Waterslide

## Usage

```
Usage: waterslide [options] url
   
--host <value>
           defaults to localhost
--port <value>
           defaults to 8090
--ttl <value>
           defaults to 30 seconds
url
           required, URL to poll and serve
```


## Example run

### Server
```bash
# java -jar waterslide.jar --host localhost --port 8090 https://crest-tq.eveonline.com/sovereignty/campaigns/
Server starting on localhost:8090, serving https://crest-tq.eveonline.com/sovereignty/campaigns/ with updates every 30 seconds
```

### Client
```JSON
21:36:00 {"initial":{"totalCount_str":"237","items":[{"eventType_str":"1","campaignID":27853,"eventType":1,"sourceSolarsystem":{"id_str":"30002112","href":...
21:36:30 {"diff":[{"op":"replace","path":"/items/0/attackers/score","value":0.8},{"op":"replace","path":"/items/0/defender/score","value":0.2},{"op":"replace","path":"/items/19/attackers/score","value":0.95},{"op":"replace","path":"/items/19/defender/score","value":0.05},{"op":"replace","path":"/items/40/attackers/score","value":0.75},{"op":"replace","path":"/items/40/defender/score","value":0.25}]}
```