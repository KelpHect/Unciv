use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use zeroize::ZeroizeOnDrop;

use super::WorkerClientError;

const KEY_BYTES: usize = 32;
pub(super) const TAG_BYTES: usize = 32;
pub(super) const NONCE_BYTES: usize = 16;
const REQUEST_DOMAIN: &[u8] = b"UNCIV-WORKER-V2\0request\0";
const RESPONSE_DOMAIN: &[u8] = b"UNCIV-WORKER-V2\0response\0";

#[derive(Clone, Copy)]
pub(super) enum FrameDirection {
    Request,
    Response,
}

#[derive(Clone, ZeroizeOnDrop)]
pub struct WorkerIdentityKey([u8; KEY_BYTES]);

impl WorkerIdentityKey {
    pub fn from_hex(value: &str) -> Result<Self, WorkerClientError> {
        if value.len() != KEY_BYTES * 2 {
            return Err(WorkerClientError::Identity);
        }
        let mut bytes = [0_u8; KEY_BYTES];
        for (index, byte) in bytes.iter_mut().enumerate() {
            let offset = index * 2;
            *byte = u8::from_str_radix(&value[offset..offset + 2], 16)
                .map_err(|_| WorkerClientError::Identity)?;
        }
        Ok(Self(bytes))
    }

    #[cfg(test)]
    pub(crate) fn for_test() -> Self {
        Self([0x55; KEY_BYTES])
    }
}

pub(super) fn sign_frame(
    key: &WorkerIdentityKey,
    direction: FrameDirection,
    nonce: &[u8; NONCE_BYTES],
    payload: &[u8],
) -> Result<[u8; TAG_BYTES], WorkerClientError> {
    let size = u32::try_from(payload.len()).map_err(|_| WorkerClientError::FrameTooLarge)?;
    let mut mac =
        Hmac::<Sha256>::new_from_slice(&key.0).map_err(|_| WorkerClientError::Identity)?;
    mac.update(match direction {
        FrameDirection::Request => REQUEST_DOMAIN,
        FrameDirection::Response => RESPONSE_DOMAIN,
    });
    mac.update(nonce);
    mac.update(&size.to_be_bytes());
    mac.update(payload);
    Ok(mac.finalize().into_bytes().into())
}

pub(super) fn verify_frame(
    key: &WorkerIdentityKey,
    direction: FrameDirection,
    nonce: &[u8; NONCE_BYTES],
    payload: &[u8],
    tag: &[u8],
) -> Result<(), WorkerClientError> {
    let size = u32::try_from(payload.len()).map_err(|_| WorkerClientError::FrameTooLarge)?;
    let mut mac =
        Hmac::<Sha256>::new_from_slice(&key.0).map_err(|_| WorkerClientError::Identity)?;
    mac.update(match direction {
        FrameDirection::Request => REQUEST_DOMAIN,
        FrameDirection::Response => RESPONSE_DOMAIN,
    });
    mac.update(nonce);
    mac.update(&size.to_be_bytes());
    mac.update(payload);
    mac.verify_slice(tag)
        .map_err(|_| WorkerClientError::Identity)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_keys_are_exact_and_frame_tags_bind_size_and_payload() {
        let key = WorkerIdentityKey::from_hex(&"55".repeat(KEY_BYTES)).unwrap();
        assert!(WorkerIdentityKey::from_hex("55").is_err());
        assert!(WorkerIdentityKey::from_hex(&"zz".repeat(KEY_BYTES)).is_err());
        let nonce = [7_u8; NONCE_BYTES];
        let tag = sign_frame(&key, FrameDirection::Request, &nonce, b"request").unwrap();
        verify_frame(&key, FrameDirection::Request, &nonce, b"request", &tag).unwrap();
        assert!(verify_frame(&key, FrameDirection::Response, &nonce, b"request", &tag).is_err());
        assert!(
            verify_frame(
                &key,
                FrameDirection::Request,
                &[8; NONCE_BYTES],
                b"request",
                &tag
            )
            .is_err()
        );
        assert!(verify_frame(&key, FrameDirection::Request, &nonce, b"response", &tag).is_err());
        let mut changed = tag;
        changed[0] ^= 1;
        assert!(verify_frame(&key, FrameDirection::Request, &nonce, b"request", &changed).is_err());
    }

    #[test]
    fn cross_language_protocol_v2_tag_vector_is_stable() {
        let key = WorkerIdentityKey::from_hex(&"55".repeat(KEY_BYTES)).unwrap();
        let nonce = std::array::from_fn(|index| index as u8);
        let request =
            sign_frame(&key, FrameDirection::Request, &nonce, b"cross-language-v2").unwrap();
        let response =
            sign_frame(&key, FrameDirection::Response, &nonce, b"cross-language-v2").unwrap();
        assert_eq!(
            hex(&request),
            "9aadde07280bfbb985dd6d2838648ae5357e7d7fe6cc5884f70a9b94f200a37c"
        );
        assert_eq!(
            hex(&response),
            "69e97ebc3a5df91ec83e2730cf9290e58a5d378e5868e4a9e79aa14392feda1a"
        );
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }
}
