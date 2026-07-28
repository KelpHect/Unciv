use super::*;

const X_FORWARDED_FOR: &str = "x-forwarded-for";
const X_REAL_IP: &str = "x-real-ip";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum TrustedProxyPolicy {
    Disabled,
    Loopback,
}

impl TrustedProxyPolicy {
    pub(super) fn from_environment() -> Result<Self, &'static str> {
        Self::parse(std::env::var("UNCIV_V3_TRUSTED_PROXY").ok().as_deref())
    }

    fn parse(value: Option<&str>) -> Result<Self, &'static str> {
        match value {
            None | Some("disabled") => Ok(Self::Disabled),
            Some("loopback") => Ok(Self::Loopback),
            Some(_) => Err("UNCIV_V3_TRUSTED_PROXY must be disabled or loopback"),
        }
    }

    pub(super) fn requires_loopback_listener(self) -> bool {
        self == Self::Loopback
    }

    pub(super) fn client_ip(
        self,
        peer: SocketAddr,
        headers: &HeaderMap,
    ) -> Result<IpAddr, ApiError> {
        if self != Self::Loopback || !peer.ip().is_loopback() {
            return Ok(peer.ip());
        }
        if headers.contains_key(header::FORWARDED) || headers.contains_key(X_REAL_IP) {
            return Err(ApiError::bad_request("invalid_client_network"));
        }
        let mut forwarded = headers.get_all(X_FORWARDED_FOR).iter();
        let value = forwarded
            .next()
            .ok_or_else(|| ApiError::bad_request("invalid_client_network"))?;
        if forwarded.next().is_some() {
            return Err(ApiError::bad_request("invalid_client_network"));
        }
        let value = value
            .to_str()
            .map_err(|_| ApiError::bad_request("invalid_client_network"))?;
        if value.trim() != value || value.contains(',') {
            return Err(ApiError::bad_request("invalid_client_network"));
        }
        let address = value
            .parse::<IpAddr>()
            .map_err(|_| ApiError::bad_request("invalid_client_network"))?;
        if address.is_unspecified() || address.is_multicast() {
            return Err(ApiError::bad_request("invalid_client_network"));
        }
        Ok(address)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(address: &str) -> SocketAddr {
        format!("{address}:443").parse().unwrap()
    }

    #[test]
    fn configuration_is_closed_and_loopback_requires_a_private_listener() {
        assert_eq!(
            TrustedProxyPolicy::parse(None).unwrap(),
            TrustedProxyPolicy::Disabled
        );
        assert_eq!(
            TrustedProxyPolicy::parse(Some("loopback")).unwrap(),
            TrustedProxyPolicy::Loopback
        );
        assert!(TrustedProxyPolicy::Loopback.requires_loopback_listener());
        for invalid in ["", "any", "10.0.0.0/8", "loopback,private"] {
            assert!(TrustedProxyPolicy::parse(Some(invalid)).is_err());
        }
    }

    #[test]
    fn untrusted_peers_cannot_spoof_forwarding_headers() {
        let mut headers = HeaderMap::new();
        headers.insert(X_FORWARDED_FOR, "203.0.113.9".parse().unwrap());
        headers.insert(header::FORWARDED, "for=198.51.100.2".parse().unwrap());
        assert_eq!(
            TrustedProxyPolicy::Disabled
                .client_ip(peer("192.0.2.44"), &headers)
                .unwrap(),
            "192.0.2.44".parse::<IpAddr>().unwrap()
        );
        assert_eq!(
            TrustedProxyPolicy::Loopback
                .client_ip(peer("192.0.2.44"), &headers)
                .unwrap(),
            "192.0.2.44".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn loopback_proxy_requires_one_unambiguous_valid_address() {
        let policy = TrustedProxyPolicy::Loopback;
        let loopback = peer("127.0.0.1");
        for value in [
            None,
            Some(""),
            Some("203.0.113.5, 127.0.0.1"),
            Some(" 203.0.113.5"),
            Some("0.0.0.0"),
            Some("ff02::1"),
            Some("not-an-ip"),
        ] {
            let mut headers = HeaderMap::new();
            if let Some(value) = value {
                headers.insert(X_FORWARDED_FOR, value.parse().unwrap());
            }
            assert!(policy.client_ip(loopback, &headers).is_err(), "{value:?}");
        }

        let mut headers = HeaderMap::new();
        headers.append(X_FORWARDED_FOR, "203.0.113.5".parse().unwrap());
        headers.append(X_FORWARDED_FOR, "198.51.100.7".parse().unwrap());
        assert!(policy.client_ip(loopback, &headers).is_err());

        let mut headers = HeaderMap::new();
        headers.insert(X_FORWARDED_FOR, "2001:db8::17".parse().unwrap());
        assert_eq!(
            policy.client_ip(loopback, &headers).unwrap(),
            "2001:db8::17".parse::<IpAddr>().unwrap()
        );
    }
}
