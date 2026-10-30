# NeoGold: Bangla Microcopy Review and Sign-Off Sheet

| Field | Value |
|---|---|
| Document | Bangla String Review (PRD Section 9b, UI/UX Guidelines Section 10.4) |
| Version | 0.1.0-draft |
| Status | **Draft for Nurse Panel** |
| Target Audience | Senior Staff Nurses, Upazila Health Complex Clinical Leads, Neonatology Advisors |
| Purpose | Clinical validation and comprehension review of user-facing microcopy in Bengali |

---

## 1. Nurse Panel Review Guidelines

1. **Intended Use Framing**: NeoGold is decision support for trained health workers. It suggests a next step; the health worker decides using clinical judgment. It never diagnoses.
2. **Never False Reassurance**: Words like "safe" (নিরাপদ), "normal" (স্বাভাবিক), "healthy" (সুস্থ), "fine" (ঠিক আছে), or "no jaundice" (জন্ডিস নেই) are banned.
3. **Clinical Numbers**: Bilirubin values, hours, weeks, and thresholds remain in Western ASCII digits by default to match Bangladeshi pathology lab reports and registers.
4. **Comprehension**: Short, direct sentences with verbs first where possible.

---

## 2. Core Clinical Microcopy Review Table

All Bengali strings below are currently **Draft for nurse panel review**.

| Key | English Source | Draft Bangla Translation | Context / Where Shown | Status |
|---|---|---|---|---|
| `action_refer_urgent` | Refer urgently now | এখনই জরুরি রেফার করুন | S14, S20 Urgent referral action header | Draft for nurse panel |
| `action_blood_test` | Blood test today | আজই রক্ত পরীক্ষা করান | S14, S20 Blood test action header | Draft for nurse panel |
| `action_recheck` | Screen again in %d hours | %d ঘণ্টা পর আবার পরীক্ষা করুন | S20 Recheck action header | Draft for nurse panel |
| `action_no_further` | No further action from this screen | এই পরীক্ষা থেকে আর কিছু করার নেই | S20 Routine care action header | Draft for nurse panel |
| `next_no_further` | Continue routine care. Watch for danger signs. | নিয়মিত যত্ন চালিয়ে যান। বিপদচিহ্নের দিকে নজর রাখুন। | S20 Next step line for routine care | Draft for nurse panel |
| `next_blood_test` | Send for a blood test today. | আজই রক্ত পরীক্ষার জন্য পাঠান। | S20 next step line for "Blood test today" (doc 6 Section 3) | Draft for nurse panel |
| `next_recheck` | Check again at %1$s. Come back sooner if danger signs appear. | %1$s সময়ে আবার দেখুন। বিপদচিহ্ন দেখা দিলে আরও আগে ফিরে আসুন। | S20 next step line for "Screen again in N hours" (doc 6 Section 3) | Draft for nurse panel |
| `next_use_blood_test` | Use a blood test. | রক্ত পরীক্ষা করান। | S20 and S14 next step line on a "cannot screen" card (doc 6 Section 3) | Draft for nurse panel |
| `next_doctor_review` | This baby needs a doctor's review. | এই শিশুকে ডাক্তার দেখাতে হবে। | S20 and S14 next step line when the baby is over 14 days (doc 6 Section 3) | Draft for nurse panel |
| `confirm_blood` | Always confirm with a blood test. | সবসময় রক্ত পরীক্ষা করে নিশ্চিত হোন। | S04, S20 Mandatory safety reminder | Draft for nurse panel |
| `experimental` | Experimental. Not yet validated in Bangladesh. | পরীক্ষামূলক। বাংলাদেশে এখনো যাচাই করা হয়নি। | S09, S20 Persistent amber banner | Draft for nurse panel |
| `cannot_screen` | NeoGold cannot screen this baby. Use a blood test. | নিওগোল্ড এই শিশুকে পরীক্ষা করতে পারবে না। রক্ত পরীক্ষা করান। | S14 Cannot screen rule outcome | Draft for nurse panel |
| `cannot_screen_14d` | Jaundice after two weeks needs a doctor's review. | দুই সপ্তাহের পরেও জন্ডিস থাকলে ডাক্তার দেখাতে হবে। | S14 Age > 14 days outcome | Draft for nurse panel |
| `q_early_jaundice` | Did yellow color appear in the first 24 hours of life? | জন্মের প্রথম ২৪ ঘণ্টার মধ্যে কি হলুদ ভাব দেখা গিয়েছিল? | S11 Safety Question 1 | Draft for nurse panel |
| `q_danger_signs` | Does the baby have any of these danger signs? | শিশুর কি এই বিপদচিহ্নগুলোর কোনোটি আছে? | S12 Safety Question 2 | Draft for nurse panel |
| `danger_feeding` | Not feeding well | ঠিকমতো খাচ্ছে না | S12 Danger sign 1 | Draft for nurse panel |
| `danger_sleepy` | Unusually sleepy or hard to wake | অস্বাভাবিক ঘুমঘুম ভাব বা জাগানো কঠিন | S12 Danger sign 2 | Draft for nurse panel |
| `danger_cry` | High-pitched cry | তীক্ষ্ণ গলায় কান্না | S12 Danger sign 3 | Draft for nurse panel |
| `danger_arching` | Arching of the back or neck | পিঠ বা ঘাড় পেছনে বেঁকে যাওয়া | S12 Danger sign 4 | Draft for nurse panel |
| `q_phototherapy` | Has the baby already had phototherapy (light treatment)? | শিশুকে কি আগে ফটোথেরাপি (আলোর চিকিৎসা) দেওয়া হয়েছে? | S13 Safety Question 3 | Draft for nurse panel |
| `birth_time` | When was the baby born? | শিশুর জন্ম কখন হয়েছে? | S10 Birth time input | Draft for nurse panel |
| `ga_label` | Gestational age (weeks) | গর্ভকাল (সপ্তাহ) | S10 Gestational age input | Draft for nurse panel |
| `place_card` | Place the card on the baby's chest | কার্ডটি শিশুর বুকের ওপর রাখুন | S15, S16 Capture guidance | Draft for nurse panel |
| `hold_still` | Hold the phone steady | ফোন স্থির রাখুন | S16 Live capture overlay | Draft for nurse panel |
| `glare` | Too much glare. Move away from the window. | আলোর প্রতিফলন বেশি। জানালা থেকে সরে আসুন। | S17 Retake feedback | Draft for nurse panel |
| `card_invalid` | This card could not be verified. It cannot be used for screening. | কার্ডটি যাচাই করা যায়নি। এই কার্ড দিয়ে পরীক্ষা করা যাবে না। | S06 Card verification error | Draft for nurse panel |
| `register_label` | Facility register no. (optional) | রেজিস্টার নম্বর (ঐচ্ছিক) | S20 Screening log entry | Draft for nurse panel |
| `register_hint` | Do not enter names, phone numbers, or birth registration numbers. | নাম, ফোন নম্বর বা জন্ম নিবন্ধন নম্বর লিখবেন না। | S20 Register number input | Draft for nurse panel |
| `register_rejected` | This looks like a phone or ID number. Use the facility register number only. | এটি ফোন বা পরিচয়পত্রের নম্বরের মতো দেখাচ্ছে। শুধু রেজিস্টার নম্বর লিখুন। | S20 Identifier rejection | Draft for nurse panel |
| `phone_qualified` | This phone can be used for screening. | এই ফোন দিয়ে পরীক্ষা করা যাবে। | S08 Qualification pass | Draft for nurse panel |
| `phone_research_only` | This phone's camera changes colors unevenly, so it can be used for research only. | এই ফোনের ক্যামেরা রং অসমানভাবে বদলায়, তাই এটি শুধু গবেষণার কাজে ব্যবহার করা যাবে। | S08 Qualification fail | Draft for nurse panel |
| `battery_steps_title` | Keep NeoGold reminders working | নিওগোল্ডের রিমাইন্ডার চালু রাখুন | S05 Battery settings guide | Draft for nurse panel |
| `upload_now` | Upload now | এখনই আপলোড করুন | R07 Manual upload button | Draft for nurse panel |
| `consent_question` | Has the baby's parent or guardian agreed to this screening? | শিশুর মা-বাবা বা অভিভাবক কি এই পরীক্ষায় সম্মতি দিয়েছেন? | S10a Guardian consent | Draft for nurse panel |
| `consent_no` | No screening without consent. Use your clinical judgment or a blood test. | সম্মতি ছাড়া পরীক্ষা করা যাবে না। আপনার ক্লিনিক্যাল বিবেচনা বা রক্ত পরীক্ষা ব্যবহার করুন। | S10a Consent refused screen | Draft for nurse panel |
| `result_suggested` | Suggested next step | পরামর্শকৃত পরবর্তী পদক্ষেপ | S20 Above action header | Draft for nurse panel |
| `result_you_decide` | You decide, using your clinical judgment. | আপনার ক্লিনিক্যাল বিবেচনা দিয়ে আপনিই সিদ্ধান্ত নিন। | S20 Below action header | Draft for nurse panel |
| `upload_waiting` | %d waiting to upload | %dটি আপলোডের অপেক্ষায় | R02, R07 Research queue | Draft for nurse panel |
| `upload_clock_wrong` | Phone clock is wrong. Set the date and time automatically, then tap Upload now. | ফোনের সময় ভুল। তারিখ ও সময় স্বয়ংক্রিয়ভাবে সেট করুন, তারপর "এখনই আপলোড করুন" চাপুন। | R07 Clock mismatch error | Draft for nurse panel |
| `upload_update_app` | Update NeoGold to keep uploading. | আপলোড চালিয়ে যেতে নিওগোল্ড আপডেট করুন। | R07 Protocol update error | Draft for nurse panel |
| `upload_reenroll` | This phone must be enrolled again. Contact your study coordinator. | এই ফোনটি আবার নিবন্ধন করতে হবে। আপনার স্টাডি কোঅর্ডিনেটরের সাথে যোগাযোগ করুন। | R07 Re-enrollment prompt | Draft for nurse panel |
| `upload_removed` | This phone was removed from the study. Data not yet uploaded can still be exported by USB. | এই ফোনটি স্টাডি থেকে বাদ দেওয়া হয়েছে। যে তথ্য এখনো আপলোড হয়নি তা ইউএসবি দিয়ে এক্সপোর্ট করা যাবে। | R07 Revocation notice | Draft for nurse panel |
| `enroll_confirm` | Join %1$s, run by %2$s? Study data will be sent to %3$s. | %2$s পরিচালিত %1$s স্টাডিতে যোগ দেবেন? স্টাডির তথ্য %3$s-এ পাঠানো হবে। | R08 Study confirmation | Draft for nurse panel |
| `enroll_code_invalid` | Code not recognised. Check it and try again. | কোডটি চেনা যায়নি। দেখে আবার চেষ্টা করুন। | R08 Code rejection | Draft for nurse panel |
| `enroll_code_new` | Ask your coordinator for a new code. | কোঅর্ডিনেটরের কাছে নতুন কোড চান। | R08 Expired code error | Draft for nurse panel |
| `participant_code_taken` | This participant code is already in use. Check the consent form. | এই অংশগ্রহণকারী কোডটি আগেই ব্যবহার করা হয়েছে। সম্মতিপত্রটি দেখুন। | R03 Code conflict error | Draft for nurse panel |
| `pin_locked` | Too many wrong PINs. Try again in %d minutes. | অনেকবার ভুল পিন দেওয়া হয়েছে। %d মিনিট পর আবার চেষ্টা করুন। | R01 PIN lockout screen | Draft for nurse panel |
| `clock_now` | Phone time now: %s | ফোনে এখনকার সময়: %s | S10 Clock sanity check | Draft for nurse panel |
| `clock_wrong_block` | The phone's date or time looks wrong. Set the date and time automatically, then try again. | ফোনের তারিখ বা সময় ভুল মনে হচ্ছে। তারিখ ও সময় স্বয়ংক্রিয়ভাবে সেট করে আবার চেষ্টা করুন। | S10 Blocked screening | Draft for nurse panel |
| `clock_auto_off` | Automatic date and time is off. Turn it on so the baby's age is calculated correctly. | স্বয়ংক্রিয় তারিখ ও সময় বন্ধ আছে। শিশুর বয়স ঠিকভাবে হিসাব করতে এটি চালু করুন। | S10 Battery/clock alert | Draft for nurse panel |
| `study_settings_mismatch` | Study settings do not match. Contact your coordinator. | স্টাডির সেটিংস মিলছে না। আপনার কোঅর্ডিনেটরের সাথে যোগাযোগ করুন। | R08 Setting profile error | Draft for nurse panel |
| `forehead_photo_problem` | The forehead photo could not be used. Place the card flat on the forehead and try again, or continue without it. | কপালের ছবিটি ব্যবহার করা যায়নি। কার্ডটি কপালে সমতলভাবে রেখে আবার তুলুন, অথবা ছবি ছাড়াই এগিয়ে যান। | S18 after a forehead photo was refused | Draft for nurse panel |
| `forehead_photo_limit` | The forehead photo could not be used after three tries. Continue without it. | তিনবার চেষ্টা করেও কপালের ছবি ব্যবহার করা যায়নি। ছবি ছাড়াই এগিয়ে যান। | S18 after three tries | Draft for nurse panel |

---

## 3. Nurse Panel Sign-Off Block

| Role | Name | Signature / Confirmation | Date |
|---|---|---|---|
| Nurse Panel Lead | | | |
| Senior Staff Nurse Representative | | | |
| Community Health Worker Lead | | | |
| Clinical Project Lead | Dr. Farhan / Clinical Advisor | | |
