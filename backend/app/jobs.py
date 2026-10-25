from __future__ import annotations

import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.db import Capture as DbCapture
from app.models.db import Session as DbSession


def complete_backstop_sessions(db: Session) -> int:
    """Nightly backstop job (doc 7 Section 5.3):
    Completes any RECEIVING session with no missing images.
    Returns number of sessions completed.
    """
    today = datetime.datetime.now(datetime.UTC).date()
    receiving_sessions = (
        db.execute(select(DbSession).where(DbSession.status == "RECEIVING").with_for_update())
        .scalars()
        .all()
    )

    completed_count = 0
    for sess in receiving_sessions:
        missing = db.execute(
            select(func.count(DbCapture.id)).where(
                DbCapture.session_id == sess.id,
                DbCapture.has_image.is_(True),
                DbCapture.verified.is_(False),
            )
        ).scalar_one()

        if missing == 0:
            sess.status = "COMPLETE"
            sess.completed_on = today
            completed_count += 1

    db.commit()
    return completed_count
