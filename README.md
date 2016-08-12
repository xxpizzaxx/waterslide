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
![client example output](https://raw.githubusercontent.com/xxpizzaxx/waterslide/master/client.png)
