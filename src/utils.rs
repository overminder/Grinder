use std::fmt;

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
