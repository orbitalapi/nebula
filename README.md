# Nebula - Test ecosystems
Nebula is a DSL for quickly defining test ecosystems - a combination of 
docker images and associated behaviour.

Under the hood, Nebular uses TestContainers to pull in and start 
the relevant containers, then scripts them.

Use Nebula to:
 * Define a Kafka broker that emits a message periodically
 * Deploy a test S3 instance on `localstack` with preconfigured content
 * Script the deployment of a full db, primed with data, which accepts writes

Nebula is part of the test and demo infrastructure at [Orbital](https://orbitalhq.com) 

