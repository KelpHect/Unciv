fn main() {
    if let Err(error) = unciv_authoritative_server::release_bundle::run(std::env::args().skip(1)) {
        eprintln!("{error}");
        std::process::exit(2);
    }
}
