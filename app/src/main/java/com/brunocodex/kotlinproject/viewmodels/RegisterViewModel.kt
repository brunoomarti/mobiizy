package com.brunocodex.kotlinproject.viewmodels

import androidx.lifecycle.ViewModel
import com.brunocodex.kotlinproject.model.ProfileType

class RegisterViewModel : ViewModel() {
    var currentStep: Int = 0

    // Contexto de autenticacao (nao persiste em draft)
    var isGoogleAccount: Boolean = false
    var lockNameFromProvider: Boolean = false
    var lockEmailFromProvider: Boolean = false
    var passwordRequired: Boolean = true

    var profileType: ProfileType? = null

    // Básico
    var name: String? = null
    var email: String? = null
    var phone: String? = null
    var cpf: String? = null // pode virar "document"
    var date: String? = null

    // Endereço
    var cep: String? = null
    var street: String? = null
    var number: String? = null
    var neighborhood: String? = null
    var city: String? = null
    var state: String? = null

    // Credenciais
    var password: String? = null

    // Foto de perfil (bytes/extensao nao persistem em draft)
    var profilePhotoUrl: String? = null
    var pendingProfilePhotoBytes: ByteArray? = null
    var pendingProfilePhotoExtension: String? = null

    fun toDraftMap(): Map<String, Any?> {
        return mapOf(
            "currentStep" to currentStep,
            "profileType" to profileType?.name, // salva enum como string
            "name" to name,
            "email" to email,
            "phone" to phone,
            "cpf" to cpf,
            "date" to date,
            "profilePhotoUrl" to profilePhotoUrl,
            "cep" to cep,
            "street" to street,
            "number" to number,
            "neighborhood" to neighborhood,
            "city" to city,
            "state" to state
            // "password" to password // <- evitar salvar senha no Firestore
        )
    }

    fun restoreFromMap(data: Map<String, Any>) {
        currentStep = (data["currentStep"] as? Number)?.toInt() ?: 0

        profileType = (data["profileType"] as? String)?.let {
            runCatching { ProfileType.valueOf(it) }.getOrNull()
        }

        name = data["name"] as? String
        email = data["email"] as? String
        phone = data["phone"] as? String
        cpf = data["cpf"] as? String
        date = data["date"] as? String
        profilePhotoUrl = data["profilePhotoUrl"] as? String
        cep = data["cep"] as? String
        street = data["street"] as? String
        number = data["number"] as? String
        neighborhood = data["neighborhood"] as? String
        city = data["city"] as? String
        state = data["state"] as? String

        // Se quiser MESMO salvar senha no draft, restaure aqui também
        // password = data["password"] as? String
    }

    fun toUserMap(): Map<String, Any?> {
        return mapOf(
            "profileType" to profileType?.name,
            "name" to name,
            "email" to email,
            "phone" to phone,
            "cpf" to cpf,
            "date" to date,
            "photoUrl" to profilePhotoUrl,
            "photoURL" to profilePhotoUrl,
            "profilePhotoUrl" to profilePhotoUrl,
            "avatarUrl" to profilePhotoUrl,
            "cep" to cep,
            "street" to street,
            "number" to number,
            "neighborhood" to neighborhood,
            "city" to city,
            "state" to state,
            "profileCompleted" to true
        )
    }
}
