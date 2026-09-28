package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/c4.md.
 * Upstream document SHA-256: 8638e33d9c4070b708951ddaed6c3c03e36bf3f10380c87928c8efd630789704
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:c4-doc-fixtures
 */
internal data class MermaidC4DocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialC4DocumentationCases: List<MermaidC4DocCase> = listOf(
    MermaidC4DocCase(
        id = "001_c4_diagrams",
        title = "C4 Diagrams",
        source = """
C4Context
      title System Context diagram for Internet Banking System
      Enterprise_Boundary(b0, "BankBoundary0") {
        Person(customerA, "Banking Customer A", "A customer of the bank, with personal bank accounts.")
        Person(customerB, "Banking Customer B")
        Person_Ext(customerC, "Banking Customer C", "desc")

        Person(customerD, "Banking Customer D", "A customer of the bank, <br/> with personal bank accounts.")

        System(SystemAA, "Internet Banking System", "Allows customers to view information about their bank accounts, and make payments.")

        Enterprise_Boundary(b1, "BankBoundary") {

          SystemDb_Ext(SystemE, "Mainframe Banking System", "Stores all of the core banking information about customers, accounts, transactions, etc.")

          System_Boundary(b2, "BankBoundary2") {
            System(SystemA, "Banking System A")
            System(SystemB, "Banking System B", "A system of the bank, with personal bank accounts. next line.")
          }

          System_Ext(SystemC, "E-mail system", "The internal Microsoft Exchange e-mail system.")
          SystemDb(SystemD, "Banking System D Database", "A system of the bank, with personal bank accounts.")

          Boundary(b3, "BankBoundary3", "boundary") {
            SystemQueue(SystemF, "Banking System F Queue", "A system of the bank.")
            SystemQueue_Ext(SystemG, "Banking System G Queue", "A system of the bank, with personal bank accounts.")
          }
        }
      }

      BiRel(customerA, SystemAA, "Uses")
      BiRel(SystemAA, SystemE, "Uses")
      Rel(SystemAA, SystemC, "Sends e-mails", "SMTP")
      Rel(SystemC, customerA, "Sends e-mails to")

      UpdateElementStyle(customerA, ${'$'}fontColor="red", ${'$'}bgColor="grey", ${'$'}borderColor="red")
      UpdateRelStyle(customerA, SystemAA, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetX="5")
      UpdateRelStyle(SystemAA, SystemE, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetY="-10")
      UpdateRelStyle(SystemAA, SystemC, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetY="-40", ${'$'}offsetX="-50")
      UpdateRelStyle(SystemC, customerA, ${'$'}textColor="red", ${'$'}lineColor="red", ${'$'}offsetX="-50", ${'$'}offsetY="20")

      UpdateLayoutConfig(${'$'}c4ShapeInRow="3", ${'$'}c4BoundaryInRow="1")
        """.trimIndent(),
    ),
    MermaidC4DocCase(
        id = "002_c4_system_context_diagram_c4context",
        title = "C4 System Context Diagram (C4Context)",
        source = """
C4Context
      title System Context diagram for Internet Banking System
      Enterprise_Boundary(b0, "BankBoundary0") {
        Person(customerA, "Banking Customer A", "A customer of the bank, with personal bank accounts.")
        Person(customerB, "Banking Customer B")
        Person_Ext(customerC, "Banking Customer C", "desc")

        Person(customerD, "Banking Customer D", "A customer of the bank, <br/> with personal bank accounts.")

        System(SystemAA, "Internet Banking System", "Allows customers to view information about their bank accounts, and make payments.")

        Enterprise_Boundary(b1, "BankBoundary") {

          SystemDb_Ext(SystemE, "Mainframe Banking System", "Stores all of the core banking information about customers, accounts, transactions, etc.")

          System_Boundary(b2, "BankBoundary2") {
            System(SystemA, "Banking System A")
            System(SystemB, "Banking System B", "A system of the bank, with personal bank accounts. next line.")
          }

          System_Ext(SystemC, "E-mail system", "The internal Microsoft Exchange e-mail system.")
          SystemDb(SystemD, "Banking System D Database", "A system of the bank, with personal bank accounts.")

          Boundary(b3, "BankBoundary3", "boundary") {
            SystemQueue(SystemF, "Banking System F Queue", "A system of the bank.")
            SystemQueue_Ext(SystemG, "Banking System G Queue", "A system of the bank, with personal bank accounts.")
          }
        }
      }

      BiRel(customerA, SystemAA, "Uses")
      BiRel(SystemAA, SystemE, "Uses")
      Rel(SystemAA, SystemC, "Sends e-mails", "SMTP")
      Rel(SystemC, customerA, "Sends e-mails to")

      UpdateElementStyle(customerA, ${'$'}fontColor="red", ${'$'}bgColor="grey", ${'$'}borderColor="red")
      UpdateRelStyle(customerA, SystemAA, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetX="5")
      UpdateRelStyle(SystemAA, SystemE, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetY="-10")
      UpdateRelStyle(SystemAA, SystemC, ${'$'}textColor="blue", ${'$'}lineColor="blue", ${'$'}offsetY="-40", ${'$'}offsetX="-50")
      UpdateRelStyle(SystemC, customerA, ${'$'}textColor="red", ${'$'}lineColor="red", ${'$'}offsetX="-50", ${'$'}offsetY="20")

      UpdateLayoutConfig(${'$'}c4ShapeInRow="3", ${'$'}c4BoundaryInRow="1")
        """.trimIndent(),
    ),
    MermaidC4DocCase(
        id = "003_c4_container_diagram_c4container",
        title = "C4 Container diagram (C4Container)",
        source = """
C4Container
    title Container diagram for Internet Banking System

    System_Ext(email_system, "E-Mail System", "The internal Microsoft Exchange system", ${'$'}tags="v1.0")
    Person(customer, Customer, "A customer of the bank, with personal bank accounts", ${'$'}tags="v1.0")

    Container_Boundary(c1, "Internet Banking") {
        Container(spa, "Single-Page App", "JavaScript, Angular", "Provides all the Internet banking functionality to customers via their web browser")
        Container_Ext(mobile_app, "Mobile App", "C#, Xamarin", "Provides a limited subset of the Internet banking functionality to customers via their mobile device")
        Container(web_app, "Web Application", "Java, Spring MVC", "Delivers the static content and the Internet banking SPA")
        ContainerDb(database, "Database", "SQL Database", "Stores user registration information, hashed auth credentials, access logs, etc.")
        ContainerDb_Ext(backend_api, "API Application", "Java, Docker Container", "Provides Internet banking functionality via API")

    }

    System_Ext(banking_system, "Mainframe Banking System", "Stores all of the core banking information about customers, accounts, transactions, etc.")

    Rel(customer, web_app, "Uses", "HTTPS")
    UpdateRelStyle(customer, web_app, ${'$'}offsetY="60", ${'$'}offsetX="90")
    Rel(customer, spa, "Uses", "HTTPS")
    UpdateRelStyle(customer, spa, ${'$'}offsetY="-40")
    Rel(customer, mobile_app, "Uses")
    UpdateRelStyle(customer, mobile_app, ${'$'}offsetY="-30")

    Rel(web_app, spa, "Delivers")
    UpdateRelStyle(web_app, spa, ${'$'}offsetX="130")
    Rel(spa, backend_api, "Uses", "async, JSON/HTTPS")
    Rel(mobile_app, backend_api, "Uses", "async, JSON/HTTPS")
    Rel_Back(database, backend_api, "Reads from and writes to", "sync, JDBC")

    Rel(email_system, customer, "Sends e-mails to")
    UpdateRelStyle(email_system, customer, ${'$'}offsetX="-45")
    Rel(backend_api, email_system, "Sends e-mails using", "sync, SMTP")
    UpdateRelStyle(backend_api, email_system, ${'$'}offsetY="-60")
    Rel(backend_api, banking_system, "Uses", "sync/async, XML/HTTPS")
    UpdateRelStyle(backend_api, banking_system, ${'$'}offsetY="-50", ${'$'}offsetX="-140")
        """.trimIndent(),
    ),
    MermaidC4DocCase(
        id = "004_c4_component_diagram_c4component",
        title = "C4 Component diagram (C4Component)",
        source = """
C4Component
    title Component diagram for Internet Banking System - API Application

    Container(spa, "Single Page Application", "javascript and angular", "Provides all the internet banking functionality to customers via their web browser.")
    Container(ma, "Mobile App", "Xamarin", "Provides a limited subset to the internet banking functionality to customers via their mobile device.")
    ContainerDb(db, "Database", "Relational Database Schema", "Stores user registration information, hashed authentication credentials, access logs, etc.")
    System_Ext(mbs, "Mainframe Banking System", "Stores all of the core banking information about customers, accounts, transactions, etc.")

    Container_Boundary(api, "API Application") {
        Component(sign, "Sign In Controller", "MVC Rest Controller", "Allows users to sign in to the internet banking system")
        Component(accounts, "Accounts Summary Controller", "MVC Rest Controller", "Provides customers with a summary of their bank accounts")
        Component(security, "Security Component", "Spring Bean", "Provides functionality related to singing in, changing passwords, etc.")
        Component(mbsfacade, "Mainframe Banking System Facade", "Spring Bean", "A facade onto the mainframe banking system.")

        Rel(sign, security, "Uses")
        Rel(accounts, mbsfacade, "Uses")
        Rel(security, db, "Read & write to", "JDBC")
        Rel(mbsfacade, mbs, "Uses", "XML/HTTPS")
    }

    Rel_Back(spa, sign, "Uses", "JSON/HTTPS")
    Rel(spa, accounts, "Uses", "JSON/HTTPS")

    Rel(ma, sign, "Uses", "JSON/HTTPS")
    Rel(ma, accounts, "Uses", "JSON/HTTPS")

    UpdateRelStyle(spa, sign, ${'$'}offsetY="-40")
    UpdateRelStyle(spa, accounts, ${'$'}offsetX="40", ${'$'}offsetY="40")

    UpdateRelStyle(ma, sign, ${'$'}offsetX="-90", ${'$'}offsetY="40")
    UpdateRelStyle(ma, accounts, ${'$'}offsetY="-40")

        UpdateRelStyle(sign, security, ${'$'}offsetX="-160", ${'$'}offsetY="10")
        UpdateRelStyle(accounts, mbsfacade, ${'$'}offsetX="140", ${'$'}offsetY="10")
        UpdateRelStyle(security, db, ${'$'}offsetY="-40")
        UpdateRelStyle(mbsfacade, mbs, ${'$'}offsetY="-40")
        """.trimIndent(),
    ),
    MermaidC4DocCase(
        id = "005_c4_dynamic_diagram_c4dynamic",
        title = "C4 Dynamic diagram (C4Dynamic)",
        source = """
C4Dynamic
    title Dynamic diagram for Internet Banking System - API Application

    ContainerDb(c4, "Database", "Relational Database Schema", "Stores user registration information, hashed authentication credentials, access logs, etc.")
    Container(c1, "Single-Page Application", "JavaScript and Angular", "Provides all of the Internet banking functionality to customers via their web browser.")
    Container_Boundary(b, "API Application") {
      Component(c3, "Security Component", "Spring Bean", "Provides functionality Related to signing in, changing passwords, etc.")
      Component(c2, "Sign In Controller", "Spring MVC Rest Controller", "Allows users to sign in to the Internet Banking System.")
    }
    Rel(c1, c2, "Submits credentials to", "JSON/HTTPS")
    Rel(c2, c3, "Calls isAuthenticated() on")
    Rel(c3, c4, "select * from users where username = ?", "JDBC")

    UpdateRelStyle(c1, c2, ${'$'}textColor="red", ${'$'}offsetY="-40")
    UpdateRelStyle(c2, c3, ${'$'}textColor="red", ${'$'}offsetX="-40", ${'$'}offsetY="60")
    UpdateRelStyle(c3, c4, ${'$'}textColor="red", ${'$'}offsetY="-40", ${'$'}offsetX="10")
        """.trimIndent(),
    ),
    MermaidC4DocCase(
        id = "006_c4_deployment_diagram_c4deployment",
        title = "C4 Deployment diagram (C4Deployment)",
        source = """
C4Deployment
    title Deployment Diagram for Internet Banking System - Live

    Deployment_Node(mob, "Customer's mobile device", "Apple IOS or Android"){
        Container(mobile, "Mobile App", "Xamarin", "Provides a limited subset of the Internet Banking functionality to customers via their mobile device.")
    }

    Deployment_Node(comp, "Customer's computer", "Microsoft Windows or Apple macOS"){
        Deployment_Node(browser, "Web Browser", "Google Chrome, Mozilla Firefox,<br/> Apple Safari or Microsoft Edge"){
            Container(spa, "Single Page Application", "JavaScript and Angular", "Provides all of the Internet Banking functionality to customers via their web browser.")
        }
    }

    Deployment_Node(plc, "Big Bank plc", "Big Bank plc data center"){
        Deployment_Node(dn, "bigbank-api*** x8", "Ubuntu 16.04 LTS"){
            Deployment_Node(apache, "Apache Tomcat", "Apache Tomcat 8.x"){
                Container(api, "API Application", "Java and Spring MVC", "Provides Internet Banking functionality via a JSON/HTTPS API.")
            }
        }
        Deployment_Node(bb2, "bigbank-web*** x4", "Ubuntu 16.04 LTS"){
            Deployment_Node(apache2, "Apache Tomcat", "Apache Tomcat 8.x"){
                Container(web, "Web Application", "Java and Spring MVC", "Delivers the static content and the Internet Banking single page application.")
            }
        }
        Deployment_Node(bigbankdb01, "bigbank-db01", "Ubuntu 16.04 LTS"){
            Deployment_Node(oracle, "Oracle - Primary", "Oracle 12c"){
                ContainerDb(db, "Database", "Relational Database Schema", "Stores user registration information, hashed authentication credentials, access logs, etc.")
            }
        }
        Deployment_Node(bigbankdb02, "bigbank-db02", "Ubuntu 16.04 LTS") {
            Deployment_Node(oracle2, "Oracle - Secondary", "Oracle 12c") {
                ContainerDb(db2, "Database", "Relational Database Schema", "Stores user registration information, hashed authentication credentials, access logs, etc.")
            }
        }
    }

    Rel(mobile, api, "Makes API calls to", "json/HTTPS")
    Rel(spa, api, "Makes API calls to", "json/HTTPS")
    Rel_U(web, spa, "Delivers to the customer's web browser")
    Rel(api, db, "Reads from and writes to", "JDBC")
    Rel(api, db2, "Reads from and writes to", "JDBC")
    Rel_R(db, db2, "Replicates data to")

    UpdateRelStyle(spa, api, ${'$'}offsetY="-40")
    UpdateRelStyle(web, spa, ${'$'}offsetY="-40")
    UpdateRelStyle(api, db, ${'$'}offsetY="-20", ${'$'}offsetX="5")
    UpdateRelStyle(api, db2, ${'$'}offsetX="-40", ${'$'}offsetY="-20")
    UpdateRelStyle(db, db2, ${'$'}offsetY="-10")
        """.trimIndent(),
    ),
)
