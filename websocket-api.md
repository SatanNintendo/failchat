# Server messages

#### Chat message 
A message from a chat   
`content.author.color: String (hex color) | null (default skin color)`  
`content.badges.type: "image" | "character"`  
`content.badges.format: "raster" | "vector" | undefined (for character badge type)`  
`content.badges.description: String | null` 
```json
{
    "type": "message",
    "content": {
        "id": 52,
        "timestamp": 1499789037199,
        "author": {
            "name": "theauthor",
            "id": "abc123",
            "color": "#ffffff"
        },
        "text": "@someuser emoticon {!0} / link {!1} / image {!2}",
        "origin": "twitch",
        "highlighted": true,
        "highlightedBackground": false,
        "elements": [
            {
                "type": "emoticon",
                "origin": "7tvChannel",
                "code": "peka",
                "url": "http://peka2.tv/img/peka.png",
                "format": "raster"
            },
            {
                "type": "link",
                "domain": "osu.ppy.sh",
                "fullUrl": "https://osu.ppy.sh/s/472567",
                "shortUrl": "osu.ppy.sh/s/472567"
            },
            {
                "type": "image",
                "url": "http://peka2.tv/logo.png"
            }
        ],
        "badges": [
            {
                "type": "image",
                "format": "raster",
                "url": "https://example.com/some-image.png",
                "description": "example badge"
            },
            {
                "type": "character",
                "htmlEntity": "&#59730;",
                "color": "#eefc08"
            }
        ]
    }
}
```

#### Client configuration
`content.zoomPercent: [1..500]`
```json
{
    "type": "client-configuration",
    "content": {
        "showViewersCount": true,
        "showOriginBadges": true,
        "showUserBadges": true,
        "zoomPercent": 100,
        "hideDeletedMessages" : false,
        "showHiddenMessages": false,
        "clickTransparency": false,
        "showClickTransparencyIcon": false,
        "nativeClient": {
            "backgroundColor": "#000000ff",
            "coloredNicknames": false,
            "hideMessages" : false,
            "hideMessagesAfter" : 60,
            "showStatusMessages" : true
        },
        "externalClient": {
            "backgroundColor": "#00000000",
            "coloredNicknames": true,
            "hideMessages" : true,
            "hideMessagesAfter" : 20,
            "showStatusMessages" : false
        },
        "enabledOrigins": {
            "peka2tv": false,
            "twitch": true,
            "goodgame": true,
            "youtube": false,
            "cybergame": false
        },
        "deletedMessagePlaceholder": {
            "text": "message deleted {!0}",
            "elements": [
                {
                    "type": "emoticon",
                    "code": "Kappa",
                    "url": "https://static-cdn.jtvnw.net/emoticons/v1/25/2.0",
                    "format": "raster"
                }
            ]
        }
    }
}
```

#### Origins status
The list of connected and disconnected origins.   
```json
{
    "type": "origins-status",
    "content": {
        "twitch": "connected",
        "youtube": "disconnected",
        "peka2tv": "connected",
        "goodgame": "disconnected",
        "cybergame": "disconnected"
    }
}
```

#### Viewers message
The currently connected origins and the viewer counts of their channels. 
`null` means that the viewer count could not be retrieved, but the counter is enabled 
```json
{
    "type": "viewers-count",
    "content": {
        "peka2tv": 100,
        "twitch": 200,
        "goodgame": null
    }
}
```

#### Delete message 
Removes a message from the chat
```json
{
    "type": "delete-message",
    "content": {
        "messageId": 123
    }
}
```

#### Clear chat
Clears all messages in the chat
```json
{
    "type": "clear-chat",
    "content": {}
}
```


# Client messages

#### Client configuration request 
```json
{
    "type": "client-configuration",
    "content": {}
}
```

#### Delete message
A client request to delete a message
```json
{
    "type": "delete-message",
    "content": {
        "messageId": 123
    }
}
```

#### Ignore user
A client request to block messages from a user
```json
{
    "type": "ignore-author",
    "content": {
        "authorId": "badauthor#twitch"
    }
}
```

#### Viewers count request  
```json
{
    "type": "viewers-count",
    "content": {}
}
```

#### Connected origins request
```json
{
    "type": "origins-status",
    "content": {}
}
```
