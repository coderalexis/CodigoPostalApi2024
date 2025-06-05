# CodigoPostalApi2024

# Zip-Code API
API to retrieve Zip Codes in Mexico.

## Tools and Technologies Used
- [IntelliJ IDEA 2024.2](https://www.jetbrains.com/idea/)
- [Spring Boot 3](https://spring.io/projects/spring-boot)
- [Spring Web](https://spring.io/guides/gs/rest-service/)
- [Lombok](https://projectlombok.org/)
- [Java 17](https://openjdk.java.net/projects/jdk/17/)

## API Endpoints

### Retrieve Zip Code

**Endpoint:**  
`GET http://127.0.0.1:8080/zip-code/{zipcode}`

**Example Request:**  
`GET http://127.0.0.1:8080/zip-code/06140`

**Example Response:**

```json
{
  "zip_code": "06140",
  "locality": "Ciudad de México",
  "federal_entity": "Ciudad de México",
  "settlements": [
    {
      "name": "Condesa",
      "zone_type": "Urbano",
      "settlement_type": "Colonia"
    }
  ],
  "municipality": "Cuauhtémoc"
}
```

### Retrieve Zip Code

**Endpoint:**
`GET http://localhost:8080/zip-codes?federal_entity={federalEntity}`

**Example Request:**
`GET http://localhost:8080/zip-codes?federal_entity=cuautitlan`

**Example Response:**
```json
[
  {
    "zip_code": "54710",
    "locality": "Cuautitlán Izcalli",
    "federal_entity": "Estado de México",
    "settlements": [
      {
        "name": "Residencial Las Flores",
        "zone_type": "Urbano",
        "settlement_type": "Colonia"
      },
      {
        "name": "Barrio Santa Rosa",
        "zone_type": "Urbano",
        "settlement_type": "Colonia"
      }
    ],
    "municipality": "Cuautitlán Izcalli"
  },
  {
    "zip_code": "54720",
    "locality": "Cuautitlán Izcalli",
    "federal_entity": "Estado de México",
    "settlements": [
      {
        "name": "Residencial Las Águilas",
        "zone_type": "Urbano",
        "settlement_type": "Colonia"
      }
    ],
    "municipality": "Cuautitlán Izcalli"
  }
]
```

**Error Response**

If a zip code is not found, a 404 HTTP code will be returned and an empty response will be displayed.

```json
{
  "error": "No postal code found:100000000"
}
```

## Project Setup and Execution

- To test the project, clone the repository and open it locally.
- Ensure you have the `CPdescarga.txt` file in `C:/home/CPdescarga.txt`. If the file does not exist there, the application will automatically use the copy bundled in its resources. You can download the file [here](https://www.correosdemexico.gob.mx/SSLServicios/ConsultaCP/CodigoPostal_Exportar.aspx).



## Performance Test Results with Apache Bench

Performance tests were conducted on the endpoint `http://127.0.0.1:8080/zip-code/70000`.

### Test Details:

- **Server Hostname**: 127.0.0.1
- **Server Port**: 8080
- **Document Path**: /zip-code/70000
- **Document Length**: 103 bytes
- **Concurrency Level**: 1,000
- **Total Requests**: 100,000

### Results:

- **Time taken for tests**: 15.651 seconds
- **Complete requests**: 100,000
- **Failed requests**: 0
- **Total transferred**: 29,700,000 bytes
- **HTML transferred**: 10,300,000 bytes
- **Requests per second (mean)**: 6389.36 [#/sec]
- **Time per request (mean)**: 156.510 [ms]
- **Time per request (across all concurrent requests)**: 0.157 [ms]
- **Transfer rate**: 1853.16 [Kbytes/sec] received

### Connection Times (ms):

| Metric | Min | Mean | Median | Max |
|--------|-----|------|--------|-----|
| Connect | 0 | 0 | 0 | 1 |
| Processing | 31 | 155 | 155 | 193 |
| Waiting | 3 | 78 | 78 | 187 |
| Total | 31 | 155 | 155 | 193 |


### Percentage of the Requests Served within a Certain Time (ms):

- **50%**: 155 ms
- **66%**: 158 ms
- **75%**: 160 ms
- **80%**: 162 ms
- **90%**: 168 ms
- **95%**: 175 ms
- **98%**: 185 ms
- **99%**: 190 ms
- **100%**: 194 ms (longest request)

### Conclusions:

- The server successfully handled 100,000 requests without any errors.
- Despite the high concurrency, the server responded efficiently with a rate of 6389.36 requests per second.
- 99% of the requests were completed in 190 ms or less.












