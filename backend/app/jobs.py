from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.db import Capture as DbCapture
from app.models.db import Participant, Site, Study
from app.models.db import Session as DbSession
from app.study_time import study_today


def zone_of_session(db: Session, sess: DbSession) -> str:
    """The time zone of the study a session belongs to (through its participant's site)."""
    return db.execute(
        select(Study.time_zone)
        .join(Site, Site.study_id == Study.id)
        .join(Participant, Participant.site_id == Site.id)
        .where(Participant.id == sess.participant_id)
    ).scalar_one()


def complete_backstop_sessions(db: Session) -> int:
    """Nightly backstop job (doc 7 Section 5.3):
    Completes any RECEIVING session with no missing images.
    Returns number of sessions completed.
    """
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
            # the study's calendar day, not the UTC day (doc 7 Section 1)
            sess.completed_on = study_today(zone_of_session(db, sess))
            completed_count += 1

    db.commit()
    return completed_count
