package com.orbitalhq.nebula.utils

object NameGenerator {
    private val adjectives = listOf(
        "abundant", "adventurous", "agile", "amiable", "ancient", "angry", "artistic", "awesome", "beautiful",
        "bold", "brave", "bright", "calm", "charming", "cheerful", "chilly", "clever", "cloudy", "colorful",
        "cool", "courageous", "crimson", "cute", "daring", "dark", "delightful", "eager", "early", "elegant",
        "enchanting", "energetic", "fancy", "fantastic", "fast", "fearless", "fierce", "fiery", "fluffy",
        "friendly", "funny", "fuzzy", "gentle", "giant", "gifted", "glamorous", "gleaming", "glistening",
        "glorious", "golden", "graceful", "grand", "great", "happy", "hasty", "heroic", "honest", "hopeful",
        "huge", "humble", "icy", "imaginative", "impressive", "incredible", "intelligent", "jolly", "jovial",
        "joyful", "keen", "kind", "lively", "luminous", "majestic", "mellow", "mighty", "modern", "mysterious",
        "nimble", "noble", "noisy", "odd", "optimistic", "peaceful", "perky", "playful", "pleasant", "plucky",
        "polite", "proud", "quick", "quiet", "radiant", "rapid", "rare", "reliable", "resilient", "rich",
        "robust", "romantic", "rusty", "sassy", "savvy", "shimmering", "shiny", "silent", "silver", "sleek",
        "slender", "smooth", "soft", "sparkling", "speedy", "spicy", "splendid", "sporty", "stately", "stout",
        "strong", "sturdy", "swift", "talented", "tall", "terrific", "thunderous", "tranquil", "trusty",
        "twinkling", "unique", "valiant", "vast", "vibrant", "victorious", "vigorous", "vivid", "warm",
        "wealthy", "witty", "wonderful", "zany", "zealous", "zippy", "zestful", "alert", "ancient", "calm",
        "brilliant", "bouncy", "cheerful", "chirpy", "classic", "courageous", "crisp", "daring", "delightful",
        "elegant", "energetic", "enthusiastic", "exuberant", "fabulous", "fantastic", "fierce", "flashy",
        "gleeful", "graceful", "happy", "hilarious", "intelligent", "jovial", "joyful", "keen", "kind",
        "lively", "lovely", "magical", "majestic", "merry", "mirthful", "noble", "optimistic", "peaceful",
        "playful", "plucky", "radiant", "refined", "resilient", "sparkling", "spirited", "sprightly",
        "stunning", "stupendous", "sublime", "tranquil", "trustworthy", "upbeat", "vibrant", "vivacious",
        "witty", "wonderful", "youthful", "zany", "zealous", "zesty"
    )

    private val nouns = listOf(
        "aardvark", "alligator", "antelope", "armadillo", "badger", "bat", "bear", "beaver", "bison", "boar",
        "buffalo", "bull", "camel", "cat", "cheetah", "chimpanzee", "chinchilla", "cobra", "cougar", "coyote",
        "crocodile", "deer", "dingo", "dog", "donkey", "dromedary", "eagle", "elephant", "elk", "emu",
        "falcon", "ferret", "fox", "frog", "gazelle", "giraffe", "goat", "goose", "gorilla", "hamster",
        "hare", "hawk", "hippopotamus", "horse", "hyena", "iguana", "jackal", "jaguar", "kangaroo", "koala",
        "leopard", "lion", "llama", "lynx", "mandrill", "marmoset", "mole", "mongoose", "monkey", "moose",
        "mouse", "mule", "ocelot", "opossum", "ostrich", "otter", "owl", "panda", "panther", "parrot",
        "peacock", "penguin", "pig", "platypus", "pony", "porcupine", "porpoise", "possum", "puma", "rabbit",
        "raccoon", "ram", "rat", "reindeer", "rhinoceros", "salamander", "seal", "sheep", "skunk", "sloth",
        "snake", "squirrel", "stallion", "stoat", "swan", "tiger", "toad", "tortoise", "turkey", "turtle",
        "viper", "walrus", "weasel", "whale", "wolf", "wombat", "yak", "zebra", "albatross", "anaconda",
        "angelfish", "anglerfish", "ant", "anteater", "baboon", "bandicoot", "barnacle", "barracuda",
        "bee", "beetle", "bird", "blackbird", "bluebird", "bufflehead", "butterfly", "buzzard", "carp",
        "catfish", "chicken", "clam", "clownfish", "cockroach", "conch", "crab", "crane", "crayfish",
        "crow", "cuttlefish", "dove", "dragonfly", "duck", "eagle", "earthworm", "eel", "egret",
        "falcon", "finch", "flamingo", "fly", "frogfish", "gecko", "goldfish", "goose", "grouse",
        "gull", "haddock", "halibut", "hummingbird", "jellyfish", "kingfisher", "ladybug", "lobster",
        "louse", "mackerel", "magpie", "manatee", "manta", "midge", "minnow", "mollusk", "monarch",
        "mosquito", "moth", "narwhal", "octopus", "orca", "oyster", "parakeet", "pelican", "perch",
        "pigeon", "pike", "polliwog", "quail", "ray", "roach", "robin", "sardine", "scallop", "seahorse",
        "seagull", "serpent", "shark", "shrimp", "snail", "sparrow", "starfish", "stork", "swordfish",
        "tern", "tigerfish", "trout", "tuna", "urchin", "vulture", "whale", "woodpecker", "worm", "yellowtail", "zebra"
    )

    fun generateName(): String {
        val adjective = adjectives.random()
        val noun = nouns.random()
        return "$adjective-$noun"
    }
}

