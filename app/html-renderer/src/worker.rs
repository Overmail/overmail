//! The thread every mail lives on. A Blitz document stays on the thread it was made on, and Stylo
//! recurses along the tree deeper than the stack of a thread the JVM made, so the JNI calls hand
//! their work to this one thread and wait for it.

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::OnceLock;
use std::sync::mpsc::{Sender, channel};

use crate::Mail;

const STACK_SIZE: usize = 16 << 20;

type Job = Box<dyn FnOnce(&mut Mails) + Send>;

/// The mails that are open, by the handle the app holds.
pub struct Mails {
    open: HashMap<i64, Mail>,
    next: i64,
}

impl Mails {
    pub fn insert(&mut self, mail: Mail) -> i64 {
        self.next += 1;
        self.open.insert(self.next, mail);
        self.next
    }

    pub fn get(&mut self, handle: i64) -> Option<&mut Mail> {
        self.open.get_mut(&handle)
    }

    pub fn remove(&mut self, handle: i64) {
        self.open.remove(&handle);
    }
}

static WORKER: OnceLock<Sender<Job>> = OnceLock::new();

fn worker() -> &'static Sender<Job> {
    WORKER.get_or_init(|| {
        let (sender, receiver) = channel::<Job>();
        std::thread::Builder::new()
            .name("blitz".into())
            .stack_size(STACK_SIZE)
            .spawn(move || {
                // Styled on this thread alone: a mail is small, and a pool over every core would
                // take them from the ui on a phone.
                stylo_static_prefs::set_pref!("layout.threads", 1);
                let mut mails = Mails { open: HashMap::new(), next: 0 };
                for job in receiver {
                    job(&mut mails);
                }
            })
            .expect("a thread for the mails");
        sender
    })
}

/// Runs [job] on the worker and waits for it; None when it panicked.
pub fn run<R: Send + 'static>(job: impl FnOnce(&mut Mails) -> R + Send + 'static) -> Option<R> {
    let (reply, answer) = channel();
    let job: Job = Box::new(move |mails| {
        let result = catch_unwind(AssertUnwindSafe(|| job(mails))).ok();
        let _ = reply.send(result);
    });
    worker().send(job).ok()?;
    answer.recv().ok().flatten()
}
