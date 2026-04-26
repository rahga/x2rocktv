package com.rahga.x2rock.model

data class Household(
    val id: String,
    val name: String
)

data class HouseholdsResponse(
    val households: List<Household>
)

data class Group(
    val id: String,
    val name: String,
    val coordinatorId: String,
    val playerIds: List<String>,
    val playbackState: String
)

data class Player(
    val id: String,
    val name: String
)

data class GroupsResponse(
    val groups: List<Group>,
    val players: List<Player>
)
