## Google OAuth Setup
Create a file "local.properties" in the root directory of the project with the following content:
```
GOOGLE_CLIENT_ID=your-client-id-here
GOOGLE_CLIENT_SECRET=your-client-secret-here
REDIRECT_URI=http://localhost:8080/callback
```
Get the client ID and client secret by creating an OAuth 2.0 Client ID credential in the Google Cloud Console: https://console.cloud.google.com/apis/credentials. 

Add callback URL `http://localhost:8080/callback` to the list of authorized redirect URIs for the credential.

Start the server:
```
./gradlew run 
```