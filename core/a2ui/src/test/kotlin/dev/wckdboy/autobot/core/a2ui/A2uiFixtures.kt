package dev.wckdboy.autobot.core.a2ui

import dev.wckdboy.autobot.shared.a2ui.A2uiCatalog

internal object A2uiFixtures {
    const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

    /** The spec's contact form (createSurface, updateComponents, updateDataModel), without its final deleteSurface. */
    val contactForm: String by lazy {
        checkNotNull(A2uiFixtures::class.java.getResource("/a2ui/contact_form_example.jsonl")).readText()
            .lines().filter { it.isNotBlank() }.take(3).joinToString("\n")
    }

    /** One surface with every basic catalog component (the Modal's content and the second tab start hidden). */
    val gallery = """
        [{"version":"v1.0","createSurface":{"surfaceId":"all","catalogId":"$BASIC","components":[
          {"id":"root","component":"Column","children":["title","caption","row","img","video","audio","divider","list","card","tabs","modal","name","agree","size","toppings","qty","when","order"]},
          {"id":"title","component":"Text","text":"## Order a pizza"},
          {"id":"caption","component":"Text","text":"Ready in *20 minutes*","variant":"caption"},
          {"id":"row","component":"Row","justify":"spaceBetween","align":"center","children":["icon","vdiv","price"]},
          {"id":"icon","component":"Icon","name":"shoppingCart"},
          {"id":"vdiv","component":"Divider","axis":"vertical"},
          {"id":"price","component":"Text","text":{"@call":"formatString","args":{"value":"Total: ${'$'}{/total}"}}},
          {"id":"img","component":"Image","url":"https://example.com/pizza.jpg","description":"A margherita","variant":"smallFeature","fit":"cover"},
          {"id":"video","component":"Video","url":"https://example.com/how.mp4"},
          {"id":"audio","component":"AudioPlayer","url":"https://example.com/jingle.mp3","description":"Our jingle"},
          {"id":"divider","component":"Divider"},
          {"id":"list","component":"List","direction":"horizontal","children":{"path":"/specials","componentId":"special"}},
          {"id":"special","component":"Text","text":{"@path":"name"}},
          {"id":"card","component":"Card","child":"cardText"},
          {"id":"cardText","component":"Text","text":"Free delivery over 30"},
          {"id":"tabs","component":"Tabs","tabs":[{"title":"Menu","child":"menuText"},{"title":"Info","child":"infoText"}]},
          {"id":"menuText","component":"Text","text":"Margherita, Diavola"},
          {"id":"infoText","component":"Text","text":"Open until 23:00"},
          {"id":"modal","component":"Modal","trigger":"modalButton","content":"modalText"},
          {"id":"modalButton","component":"Button","variant":"borderless","child":"modalLabel","action":{"event":{"name":"noop"}}},
          {"id":"modalLabel","component":"Text","text":"Allergens"},
          {"id":"modalText","component":"Text","text":"Contains gluten and milk."},
          {"id":"name","component":"TextField","label":"Name","value":{"@path":"/form/name"},
           "checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/form/name"}}},"message":"Name is required."}]},
          {"id":"agree","component":"CheckBox","label":"Extra cheese","value":{"@path":"/form/cheese"}},
          {"id":"size","component":"ChoicePicker","label":"Size","options":[{"label":"Small","value":"s"},{"label":"Large","value":"l"}],"value":{"@path":"/form/size"}},
          {"id":"toppings","component":"ChoicePicker","label":"Toppings","variant":"multipleSelection","displayStyle":"chips","options":[{"label":"Olives","value":"olives"},{"label":"Basil","value":"basil"}],"value":{"@path":"/form/toppings"}},
          {"id":"qty","component":"Slider","label":"Quantity","min":1,"max":5,"steps":4,"value":{"@path":"/form/qty"}},
          {"id":"when","component":"DateTimeInput","label":"Delivery","enableDate":true,"enableTime":true,"value":{"@path":"/form/when"}},
          {"id":"order","component":"Button","variant":"primary","child":"orderLabel",
           "checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/form/name"}}},"message":"Add your name first."}],
           "action":{"event":{"name":"order","userMessage":"Order it","context":{"name":{"@path":"/form/name"},"size":{"@path":"/form/size"}}}}},
          {"id":"orderLabel","component":"Text","text":"Order"}],
          "dataModel":{"total":"18.50","specials":[{"name":"Diavola"},{"name":"Funghi"}],
            "form":{"name":"","cheese":false,"size":["s"],"toppings":[],"qty":2,"when":"2026-10-21T19:30:00"}}}}]
    """.trimIndent()
}
