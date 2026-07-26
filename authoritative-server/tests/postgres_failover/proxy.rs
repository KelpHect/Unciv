use std::{
    io,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::Arc,
};

use tokio::{
    io::copy_bidirectional,
    net::{TcpListener, TcpStream},
    sync::{RwLock, oneshot},
    task::JoinHandle,
};

pub(super) struct DatabaseProxy {
    address: SocketAddr,
    target: Arc<RwLock<SocketAddr>>,
    shutdown: Option<oneshot::Sender<()>>,
    task: JoinHandle<()>,
}

impl DatabaseProxy {
    pub(super) async fn start(initial_port: u16) -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .expect("bind database proxy");
        let address = listener.local_addr().expect("read database proxy address");
        let target = Arc::new(RwLock::new(localhost(initial_port)));
        let target_for_task = Arc::clone(&target);
        let (shutdown, mut shutdown_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    accepted = listener.accept() => {
                        let Ok((client, _)) = accepted else { break };
                        let target = Arc::clone(&target_for_task);
                        tokio::spawn(async move {
                            let destination = *target.read().await;
                            if let Ok(server) = TcpStream::connect(destination).await {
                                let _ = forward(client, server).await;
                            }
                        });
                    }
                }
            }
        });
        Self {
            address,
            target,
            shutdown: Some(shutdown),
            task,
        }
    }

    pub(super) fn port(&self) -> u16 {
        self.address.port()
    }

    pub(super) async fn route_to(&self, port: u16) {
        *self.target.write().await = localhost(port);
    }
}

impl Drop for DatabaseProxy {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        self.task.abort();
    }
}

async fn forward(mut client: TcpStream, mut server: TcpStream) -> io::Result<()> {
    copy_bidirectional(&mut client, &mut server).await?;
    Ok(())
}

fn localhost(port: u16) -> SocketAddr {
    SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port)
}
