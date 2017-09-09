use std::{io, fs, fmt};
use std::io::Write;

const TEST_OUTPUT: &'static str = "test.out";

fn dump_pretty<A: fmt::Debug>(path: &str, a: &A) -> io::Result<()> {
    let mut f = fs::File::create(path)?;
    write!(&mut f, "{:#?}", a)
}

pub fn assert_eq_pretty<A: fmt::Debug + Eq>(tag: &str, lhs: &A, rhs: &A) {
    let _ = fs::create_dir(TEST_OUTPUT);

    if lhs != rhs {
        dump_pretty(&format!("{}/{}.{}", TEST_OUTPUT, tag, "lhs"), lhs).unwrap();
        dump_pretty(&format!("{}/{}.{}", TEST_OUTPUT, tag, "rhs"), rhs).unwrap();
        panic!("[{}] lhs != rhs", tag);
    }
}
