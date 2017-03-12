use std::fmt;

pub fn inplace_min<A: Ord>(x: &mut A, y: A) {
    inplace_min_by(x, y, |x, y| x < y);
}

pub fn inplace_min_by<A, F: Fn(&A, &A) -> bool>(x: &mut A, y: A, lt: F) {
    if lt(&y, x) {
        *x = y;
    }
}

pub fn ensure_sorted<A: Ord + fmt::Debug>(it: &mut Iterator<Item=A>) -> Result<(), String> {
    let mut last = None;
    for a in it {
        if let Some(last) = last {
            if !(last <= a) {
                return Err(format!("Failed to assert {:?} <= {:?}", last, a));
            }
        }
        last = Some(a);
    }
    Ok(())
}
