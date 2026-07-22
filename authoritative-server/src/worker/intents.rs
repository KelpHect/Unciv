pub struct SetCityGovernanceIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub action: crate::CityGovernanceAction,
}

pub struct ResolveCityDispositionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub action: crate::CityDispositionAction,
}

pub struct CastDiplomaticVoteIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub candidate_civilization_id: Option<&'a str>,
}

pub struct ChooseGreatPersonIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_name: &'a str,
}

pub struct SetCityTileAssignmentIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub x: i32,
    pub y: i32,
    pub assignment: crate::CityTileAssignment,
}

pub struct SetSpecialistCountIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub specialist_name: &'a str,
    pub count: u32,
}

pub struct SetManualSpecialistsIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub enabled: bool,
}

pub struct ResetCitizensIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
}

pub struct SetAvoidGrowthIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub enabled: bool,
}

pub struct SetCitizenFocusIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub focus: crate::CitizenFocus,
}

pub struct SetResearchPathIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
}

pub struct AdoptPolicyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub policy_name: &'a str,
}

pub struct ChooseFreeTechnologyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
}
